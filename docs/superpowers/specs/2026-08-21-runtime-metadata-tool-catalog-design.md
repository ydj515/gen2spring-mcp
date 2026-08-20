# Deterministic Runtime Metadata and Persistent Tool Catalog Design

## 1. Purpose

This slice turns the final framework-neutral `ToolDefinition` list into a deterministic runtime metadata
artifact and publishes that metadata to an owner-scoped PostgreSQL Tool Catalog after hosted generation
succeeds.

The result establishes the contract required by a later Managed MCP Runtime without implementing dynamic
`tools/list` or `tools/call` execution in this slice.

## 2. Scope

Included:

- deterministic `RUNTIME_METADATA.json` generation from the final Tool IR
- the same metadata contract for Spring AI 1 and Spring AI 2 targets
- exact MCP input schemas and truthful output schemas
- framework-neutral HTTP execution, response, retry, pagination, and credential requirements
- metadata versioning and canonical SHA-256 checksums
- publication only for hosted generation jobs that validate and package successfully
- immutable PostgreSQL Tool Catalog persistence using the existing hosted database
- authenticated, owner-scoped Catalog list, Catalog detail, and Tool detail APIs
- Flyway migration, sandbox transport, worker publication, API, and regression tests
- PRD status synchronization for the completed P2 slice and the already-supported OpenAPI 3.1 item

Excluded:

- dynamic Managed Runtime `tools/list` and `tools/call`
- `McpJavaSdkEmitter` implementation
- credential value storage or credential management APIs
- Tool Catalog mutation, deletion, sharing, search, or cross-owner visibility
- Gateway authorization, rate limiting, audit execution records, or runtime traces
- Catalog publication for local CLI runs or unverified hosted generations

## 3. Architectural Decision

Runtime metadata is a separate product contract, not a source emitter and not an extension of the generation
manifest.

```text
OpenAPI
  -> final ToolDefinition[]
  -> RuntimeMetadataDocumentFactory
  -> CanonicalRuntimeMetadataCodec
       -> RUNTIME_METADATA.json
       -> canonical checksum
  -> generated project
  -> validate and package
  -> hosted sandbox result
  -> HostedWorker
  -> JobQueue completion transaction
       -> artifact rows
       -> tool_catalog row
       -> tool_catalog_entry rows
       -> generation_job = SUCCEEDED
```

`ToolEmitter` remains responsible for generated source files. Spring AI 1 and Spring AI 2 continue to
implement that port. A future `McpJavaSdkEmitter` will require its own runtime/profile/scaffold contract and
will consume the same Tool IR and runtime metadata semantics; it is not simulated by this work.

`GENERATION_MANIFEST.json` continues to describe generator and build lineage. `RUNTIME_METADATA.json`
describes executable Tool semantics. Mixing the two would couple a future Managed Runtime to Spring
generation profiles and would make their version lifecycles inseparable.

## 4. Ownership of the Model

The domain module owns immutable framework-neutral metadata records under a runtime metadata package. The
records contain no Jackson, Spring, filesystem, database, or MCP SDK types.

The application module owns `RuntimeMetadataDocumentFactory`. It projects the final `ToolDefinition` list
and original specification checksum into the domain model.

The existing schema conversion in `ExpectedToolSchemaFactory` is extracted into a reusable
`ToolJsonSchemaFactory` so that validation, generated `tools/list`, and runtime metadata cannot drift into
different input schemas. Tests use independent JSON literals and do not build expected values through the
production factory.

The application module also owns the canonical JSON codec because both the generation pipeline and hosted
sandbox boundary must use the same exact contract. The domain remains serialization-independent.

## 5. Runtime Metadata Contract

### 5.1 Top-level document

The generated file is named `RUNTIME_METADATA.json` and has a maximum UTF-8 size of 1,048,576 bytes.

```json
{
  "metadataVersion": "1.0",
  "specificationChecksum": "<sha256>",
  "checksum": "<canonical-payload-sha256>",
  "tools": []
}
```

The canonical payload used for `checksum` contains `metadataVersion`, `specificationChecksum`, and `tools`,
but not the checksum field itself. The persisted and generated document includes the computed checksum.

User identity, generation job ID, catalog ID, timestamps, target profile, generator version, template
version, and source tree checksum are excluded. Therefore equivalent final Tool IR generated through
different Spring AI and Java profiles produces identical runtime metadata.

### 5.2 Tool document

Each Tool contains:

- `operationId`
- `name`
- `description`
- exact MCP `inputSchema`
- `outputKind`
- `outputSchema`
- HTTP method, normalized base URL, path, and input bindings
- object request body and required-body flags
- response normalization policy
- retry policy
- pagination policy
- credential requirements

For `TYPED_DTO`, `outputSchema` is the exact normalized result schema. For `RAW_JSON`, it is the empty JSON
Schema object `{}`, which truthfully permits any JSON value. Metadata does not invent an object-only output
contract.

The base URL uses its normalized ASCII URI representation. User-info and fragments are rejected. Query
credentials and configured secret values are never embedded in the URI or metadata.

### 5.3 Credential requirements

A credential requirement contains only:

- `credentialSlot`
- `targetLocation`
- `targetName`
- `required`

It never contains `environmentVariable`, a property placeholder, or a secret value.

`credentialSlot` is the existing safe `SecretBinding.propertyName()` converted with
`Locale.ROOT` to lower case. It must match `[a-z][a-z0-9_-]{0,127}` after conversion. No truncation or hash
fallback is permitted.

The factory validates slots across the complete document. Repeated use of a slot for the same normalized
target is allowed. A slot mapped to different locations or different target names fails closed. HTTP header
target names compare case-insensitively; query and body target names remain case-sensitive.

### 5.4 Canonical encoding

Canonical JSON uses these rules:

- UTF-8 without BOM
- compact JSON and exactly one final LF
- fixed top-level and Tool field order
- Tools ordered by `name`, then `operationId`
- input properties, schema properties, bindings, and credentials ordered by their canonical keys
- required arrays, retry status codes, and other set-like values sorted
- ordered policies such as success values retain semantic order
- `BigInteger` and `BigDecimal` retain exact supported numeric values without floating-point conversion
- no timestamps, random identifiers, absolute paths, process output, or environment-dependent values

Decode performs strict duplicate detection, rejects trailing content and unknown fields, validates all
bounds, verifies the embedded checksum, re-encodes the model, and requires byte-for-byte equality with the
received document.

## 6. Generation Pipeline

After Tool planning and before the project workspace write, the pipeline creates and encodes runtime
metadata. It adds the file to `GeneratedProjectFiles` alongside the copied original OpenAPI document.

This ordering provides the following properties:

- runtime metadata participates in the source tree checksum
- the generated project and validated ZIP contain the same bytes
- metadata has no circular dependency on the source tree checksum
- metadata generation fails before compilation or publication
- preview includes `RUNTIME_METADATA.json` in the expected output paths

An unverified local generation may retain the project directory and runtime metadata for diagnostics, but it
does not produce a ZIP. Local generation never writes to the hosted Catalog.

Metadata failures use:

- code: `RUNTIME_METADATA_INVALID`
- stage: `RUNTIME_METADATA`
- safe message: `Runtime metadata could not be generated`

No offending URI, operation name, credential name, schema value, path, or secret is copied into the public
failure message.

## 7. Hosted Sandbox and Worker Boundary

The runner copies the generated metadata to `runtime-metadata.json` only after the CLI exits successfully and
the project archive, generation manifest, and validation report exist.

Because this changes the mandatory runner output set, the generation runner protocol label is incremented
from `1` to `2`. Worker readiness requires protocol `2`; old generation runner images fail readiness instead
of producing an ambiguous partial result. The import runner protocol is unchanged.

The sandbox collector expects exactly:

- `archive.zip`
- `manifest.json`
- `validation-report.json`
- `runtime-metadata.json`
- `result.json`

It rejects missing files, unexpected files, symbolic links, non-regular files, oversized content, invalid
JSON, checksum mismatch, or non-canonical bytes.

`SandboxResult` carries ordinary downloadable artifacts separately from an optional decoded runtime metadata
publication. A successful generation requires metadata. A successful specification import forbids metadata.
The runtime metadata remains present inside the generated ZIP and in PostgreSQL; it is not uploaded as a
fourth standalone S3 artifact in this slice.

The worker uploads the existing archive, manifest, and validation report first. It then passes their records
and the validated metadata publication to `JobQueue.complete`. If database completion fails or the lease is
stale, the worker deletes the uploaded objects as it does today.

## 8. Atomic Catalog Publication

The completion contract enforces:

- successful `GENERATION`: non-empty Catalog publication required
- successful `SPEC_IMPORT`: Catalog publication forbidden
- failed or cancelled job: Catalog publication forbidden
- one Catalog per generation job

The PostgreSQL adapter performs artifact insertions, Catalog insertion, Tool entry insertions, job transition,
and job event insertion in the existing completion transaction. Any failure rolls back every database change.
The job is not externally successful until its Catalog exists.

Transient database failures leave the job uncompleted so the existing lease expiry and bounded retry mechanism
remains authoritative. A successfully committed transaction cannot be replayed because the job is no longer
claimable and `generation_job_id` is unique in the Catalog.

## 9. PostgreSQL Schema

Flyway migration `V4__tool_catalog.sql` adds two immutable tables.

### 9.1 `tool_catalog`

- `id uuid primary key`
- `owner_account_id uuid not null`
- `generation_job_id uuid not null unique`
- `metadata_version varchar(16) not null`
- `specification_checksum char(64) not null`
- `metadata_checksum char(64) not null`
- `metadata_document text not null`
- `tool_count integer not null`
- `created_at timestamptz not null`

The owner and generation job use a composite foreign key to prevent cross-owner linkage. The table has a
unique `(id, owner_account_id)` identity and an owner pagination index on `(owner_account_id, created_at desc,
id desc)`.

### 9.2 `tool_catalog_entry`

- `catalog_id uuid not null references tool_catalog(id) on delete cascade`
- `ordinal integer not null`
- `tool_name varchar(128) not null`
- `operation_id varchar(128) not null`
- `metadata_document text not null`
- primary key `(catalog_id, tool_name)`
- unique `(catalog_id, ordinal)`

Exact canonical text is deliberately stored in both the aggregate document and individual Tool entries.
This bounded duplication preserves byte identity for Catalog detail while allowing indexed Tool lookup without
reconstructing or scanning a JSON array. Both representations are produced from one validated model and
inserted in one transaction.

Database constraints bound version, hashes, Tool count, text sizes, identifiers, and JSON object shape.
Application readback still decodes and validates stored metadata before returning it.

## 10. Query API

All endpoints are available only in hosted mode and require the existing authenticated OIDC account.

```text
GET /api/tool-catalogs?limit=50&cursor=...
GET /api/tool-catalogs/{catalogId}
GET /api/tool-catalogs/{catalogId}/tools/{toolName}
```

### 10.1 List

The list response contains:

- `catalogId`
- `generationId`
- `metadataVersion`
- `metadataChecksum`
- `toolCount`
- `createdAt`
- `nextCursor`

It reuses the existing opaque cursor semantics over `(created_at, id)`, ordered descending. The default limit
is 50 and the maximum is 100.

### 10.2 Catalog detail

Catalog detail returns the list fields, `specificationChecksum`, and the complete decoded metadata document.

### 10.3 Tool detail

Tool detail returns Catalog identity/version/checksum and the selected decoded Tool metadata document.

Every query includes `owner_account_id`. A missing resource and another owner's resource both return the same
404 contract. Invalid IDs, limits, or cursors return the existing safe 400 response. Missing authentication
returns 401. The API never returns environment variable names, secret values, object keys, local paths, or
worker details.

## 11. Application Boundaries

The application layer introduces separate write and read responsibilities:

- `ToolCatalogPublication` carries validated immutable metadata into job completion
- `JobQueue.complete` carries publication into the existing fenced job-completion transaction
- `ToolCatalogStore` owns owner-scoped read models
- `ToolCatalogService` applies query bounds and not-found semantics

`PostgresJobQueue` inserts Catalog rows inside the job transaction; a separate `PostgresToolCatalogStore`
implements reads. This avoids a second transaction manager or a distributed application-level transaction.
The Web controller resolves the owner through
`HostedAccountResolver` and performs presentation only. It does not query JDBC or inspect job artifacts
directly.

The existing `HostedResourceStore` remains responsible for specifications, jobs, events, and downloadable
artifacts. Catalog semantics are kept in a dedicated port instead of further widening that mixed read model.

## 12. Security and Privacy

- Secret values and environment variable names are rejected from metadata and Catalog responses.
- Metadata parsing is bounded and uses duplicate-key and trailing-token rejection.
- Base URLs reject user-info and fragments.
- Owner predicates are mandatory in every Catalog query.
- Foreign and absent resources share one 404 response.
- Catalog rows are immutable through the application API.
- Error responses use fixed messages and do not contain JSON fragments or database diagnostics.
- Database completion remains fenced by worker ID, fencing token, and live lease.
- S3 cleanup remains best-effort after stale or failed database completion.

## 13. Failure Semantics

| failure | outcome |
|---|---|
| metadata model, bounds, or canonical encoding invalid | generation fails at `RUNTIME_METADATA` |
| sandbox metadata missing, linked, oversized, or non-canonical | sandbox generation fails; no Catalog |
| cancellation before completion | uploaded objects deleted; no Catalog |
| stale fencing token or lease | uploaded objects deleted; no Catalog |
| transient PostgreSQL failure | transaction rolls back; lease recovery may retry |
| Catalog constraint or consistency violation | transaction rolls back; job is not successful |
| unknown or foreign Catalog | safe 404 |
| persisted metadata fails readback validation | fixed internal API failure; raw data not returned |

Fatal `Error` and thread interruption behavior remain unchanged and are never converted into user-level
Catalog errors.

## 14. Verification Strategy

### 14.1 Domain and application

- immutable model and bounds
- exact schema projection for nested, nullable, array-bound, enum, numeric, and raw output cases
- credential slot derivation, sharing, and collision rejection
- canonical ordering, exact decimal preservation, checksum, final LF, and 1 MiB boundary
- equivalent reordered input yields identical bytes
- Spring AI and Java profile differences do not affect metadata
- secret and environment variable absence

### 14.2 Generation and packaging

- preview contains the new file
- source checksum changes when Tool semantics change
- project and ZIP contain byte-identical metadata
- validated and unverified packaging behavior remains correct
- existing manifest and validation report contracts remain unchanged

### 14.3 Sandbox and worker

- runner output contract includes metadata
- missing, extra, linked, oversized, malformed, checksum-mismatched, and non-canonical metadata is rejected
- generation success requires metadata and import success forbids it
- artifact cleanup on stale, cancellation, or database failure
- job success cannot occur without Catalog publication

### 14.4 PostgreSQL

- Flyway migration on PostgreSQL 17.9-alpine
- atomic artifact, Catalog, Tool, job, and event commit
- rollback on every injected insertion failure
- unique generation Catalog and deterministic Tool ordinals
- owner scope and cursor ordering
- exact aggregate and Tool metadata readback

### 14.5 Web

- authentication, list pagination, Catalog detail, and Tool detail
- invalid cursor/limit and malformed IDs
- absent and cross-owner resources return indistinguishable 404 responses
- responses contain no secret, environment, path, object-key, or worker data

### 14.6 Full gate

The final verification runs affected focused suites first, followed by the repository's Java 17/21 full test,
integration test, and installed CLI gate. Deterministic outputs and the hosted PostgreSQL/container contracts
are included in the acceptance evidence.

## 15. Complexity and Bounds

Metadata construction and encoding are `O(n)` in the total Tool/schema size and use `O(n)` bounded memory.
The document is capped at 1 MiB and the existing operation selection remains capped at 1,000.

Catalog listing is bounded by the requested page size and uses the owner pagination index. Catalog and Tool
lookup use indexed identifiers. Database storage is approximately twice the metadata size because exact
aggregate and individual Tool documents are intentionally retained, plus bounded relational metadata.

## 16. Rollout and Compatibility

The migration is additive. Existing jobs, specifications, and artifacts remain readable. No backfill is
performed because previous generations do not have a runtime metadata artifact validated under version 1.0.

After deployment, only new successfully validated generation jobs create Catalogs. Local CLI output gains one
deterministic project file. The compatibility profile IDs and generated Spring AI runtime behavior remain
unchanged.

The PRD is updated to mark OpenAPI 3.1 as already supported, mark this bounded Managed Runtime metadata and
Tool Catalog query slice complete, and keep dynamic Managed Runtime execution and Gateway policy features as
future work.

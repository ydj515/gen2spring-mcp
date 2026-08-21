# Managed Runtime Credential, Policy, Audit, and Scale Design

## 1. Purpose

This slice completes the next Managed Runtime P2 boundary on top of the immutable Tool Catalog and the
credential-free runtime delivered by PR #14. It lets an authenticated owner store provider credentials,
bind them to canonical Runtime Metadata credential slots, issue scoped client grants, enforce Tool policy,
record safe execution audit data, and run the MCP data plane without replica affinity.

The result remains a single-Catalog Managed Runtime. It is not a cross-Catalog discovery or sharing Gateway.

## 2. Scope

Included:

- owner-scoped, write-only provider credential lifecycle
- envelope-encrypted credential storage with operator-managed key rotation support
- exact Catalog credential-slot binding during runtime activation
- raw API key, Bearer, and Basic credential formatting at execution time
- runtime access grants with an opaque principal label, exact allowed Tool set, expiry, and rate limit
- Tool visibility and authorization driven by the same grant policy
- PostgreSQL-coordinated rate limiting across runtime replicas
- safe, owner-scoped Tool execution audit queries
- MCP Java SDK stateless Streamable HTTP transport
- two-replica acceptance coverage without sticky sessions
- deployment, user guide, PRD, and architecture diagram synchronization

Excluded:

- cross-owner or public Catalog sharing
- cross-Catalog Tool aggregation and discovery
- direct OIDC validation in `apps/runtime`
- OAuth2 client-credentials token acquisition and refresh
- arbitrary custom signing code
- billing and usage-based charging
- mutable Tool Catalogs or automatic Catalog version migration
- secrets in generated Spring AI projects

## 3. Architectural Decision

### 3.1 Selected approach: scoped runtime grants

The control plane remains `apps/web`. It stores encrypted credentials, creates runtime bindings, and issues
additional scoped runtime grants. The data plane remains `apps/runtime`; it authenticates opaque bearer
tokens against PostgreSQL, materializes only the Tool subset allowed by the grant, resolves current active
credential values for a call, and writes bounded audit records.

```text
OIDC owner
  |
  +-- credential API ----> encrypted credential rows
  +-- runtime activation -> slot bindings + owner grant
  +-- grant API ---------> allowed Tools + rate limit + bearer digest
  +-- audit API <--------- safe execution metadata

MCP client -- grant bearer --> runtime replica A or B
                                 |
                                 +-- authenticate grant
                                 +-- grant-scoped tools/list
                                 +-- distributed rate acquire
                                 +-- credential resolve/decrypt
                                 +-- provider-egress (mTLS)
                                 +-- audit completion
```

This approach keeps the runtime independent from an external identity provider while still giving each MCP
client a stable principal and least-privilege Tool set. A separate Gateway service is not introduced.

### 3.2 Rejected approaches

Direct OIDC/JWT validation in `apps/runtime` is rejected because it couples the data plane to one identity
provider, key-refresh mechanism, and claim mapping. A separate public Gateway is rejected because it adds a
new network boundary before the required single-Catalog policy is proven.

## 4. Credential Model

### 4.1 Credential resource

A `ManagedCredential` belongs to one `AccountId` and has:

- UUID credential ID
- bounded display label, used only in owner-facing metadata
- kind: `OPAQUE`, `BEARER`, or `BASIC`
- monotonically increasing version
- encrypted payload
- creation, rotation, and optional revocation timestamps

Plaintext is accepted only by create and rotate requests. List and detail responses expose ID, label, kind,
version, state, and timestamps; they never expose plaintext, ciphertext, key IDs, nonces, or hashes.

`OPAQUE` stores one exact UTF-8 value for API-key header or query injection. `BEARER` stores only the token and
formats `Bearer <token>` at execution. `BASIC` stores username and password and formats RFC 7617 UTF-8 Basic
credentials at execution. Bearer and Basic bindings are valid only for an `Authorization` header target.

Credential payload bounds:

- label: 1..128 UTF-8 bytes, no control characters
- opaque value or bearer token: 1..8192 UTF-8 bytes, no control characters
- Basic username: 1..256 UTF-8 bytes, no colon or control characters
- Basic password: 1..4096 UTF-8 bytes, no control characters
- maximum active credentials per owner: 100

### 4.2 Encryption

`CredentialProtector` is an application port. The cryptography adapter implements AES-256-GCM envelope
encryption using the existing operator-key configuration shape:

- one random 256-bit data key per credential version
- independent 96-bit nonces for wrapped key and payload
- 128-bit GCM tags
- AAD includes contract version, owner ID, credential ID, credential version, key ID, and purpose
- active key writes new versions; configured retired keys remain read-only for decryption
- plaintext and data-key byte arrays are cleared in `finally`

Key files must be absolute, regular, non-symlink 32-byte files. POSIX group/other permissions are rejected
where supported. All protection failures use fixed messages without paths, payloads, or cryptographic data.

Java and HTTP libraries require immutable strings at the final wire boundary, so complete in-memory
zeroization cannot be guaranteed. The implementation minimizes the lifetime of decoded values and never
caches plaintext credentials in runtime server handles.

### 4.3 Slot binding

Runtime Metadata remains version `1.0`; it already contains canonical credential slot, target location,
target name, and required state. Activation accepts an exact `credentialBindings` object from slot name to
credential ID.

Rules:

- all required slots must be bound
- unknown or duplicate slots are rejected
- a credential must be active and owned by the runtime owner
- one slot maps to exactly one credential
- `OPAQUE` may target `HEADER` or `QUERY`
- `BEARER` and `BASIC` may target only header `Authorization`, case-insensitively
- `PATH` and `BODY` credential targets remain unsupported and fail before persistence
- credential IDs and versions are stored in binding rows; current active version is resolved per call
- rotating a credential updates existing runtime calls; revoking it blocks affected calls immediately

The runtime instance and all bindings are inserted atomically. Foreign and absent credentials share the same
fixed invalid-request outcome.

## 5. Access Grants and Authorization

### 5.1 Owner grant compatibility

Activation continues to return one plaintext bearer token exactly once. Its digest remains on the runtime
instance and represents an owner grant that can see and call every Tool in that immutable Catalog. This keeps
existing clients compatible.

### 5.2 Scoped grants

An owner may create additional grants for an active runtime:

- UUID grant ID
- opaque principal label, 1..128 safe characters
- non-empty exact set of Tool names from the runtime Catalog
- requests-per-minute limit from 1..6000
- lifetime no longer than the runtime and no longer than 30 days
- one-time plaintext bearer token; digest-only persistence
- optional revocation timestamp

The data plane authenticates the runtime ID and bearer token, returning a `RuntimeAccess` that contains the
runtime, grant ID, principal label, allowed Tool names, rate limit, and owner-grant flag. Missing, foreign,
expired, revoked, and mismatched grants return one identical 401 response.

The owner grant uses the configured default of 600 requests per minute. A scoped grant uses its explicit
limit. Runtime revocation or expiry invalidates every grant.

### 5.3 Tool visibility

`tools/list` and `tools/call` use the same immutable allowed-Tool set. A grant-scoped stateless server handle
contains only permitted Tool specifications. Calling a hidden Tool therefore fails at the MCP schema/registry
boundary and never reaches credential resolution, rate acquisition, provider egress, or audit start.

The handle-cache key is `(runtimeId, catalogChecksum, policyChecksum)`. It contains metadata and SDK objects
only; it never contains bearer tokens or credential plaintext.

## 6. Distributed Rate Limiting

Rate limiting applies to accepted `tools/call` attempts, before credential resolution and provider execution.
It does not apply to initialize or `tools/list`.

PostgreSQL stores one fixed UTC minute window per runtime/grant identity. A single statement derives the
window from PostgreSQL time and atomically resets the counter for a later window or increments it when below
the grant limit. The unique row constraint prevents split counters across replicas and application clock skew
cannot create a second window.

A denied call returns a safe MCP Tool error categorized as `RATE_LIMITED`; it creates an audit record with no
provider status and makes no provider request. Storage failure returns an internal MCP error and does not run
the provider call.

## 7. Execution Audit

Every authorized `tools/call` obtains a random execution ID and creates one audit row before provider egress.
The row contains only:

- execution ID, owner ID, runtime ID, grant ID or owner-grant marker
- opaque principal label
- Catalog checksum and Tool name
- `STARTED`, `SUCCEEDED`, `TOOL_ERROR`, `RATE_LIMITED`, or `INTERNAL_ERROR`
- existing safe error category, optional HTTP status, duration milliseconds
- request and response byte counts only
- started and completed timestamps

Arguments, query strings, headers, bodies, provider messages, bearer tokens, credential IDs, labels,
ciphertext, stack traces, and local paths are forbidden. Byte counts are numeric and bounded.

The audit start is persisted before credential resolution. Completion is a compare-and-set transition from
`STARTED`. If completion persistence fails after the provider call, the client receives a fixed internal MCP
error; the `STARTED` row remains available for reconciliation and the provider call is never repeated.

Owner-scoped audit queries use cursor pagination and a maximum page size of 100. Foreign runtime and audit
resources share the same 404 response.

## 8. Credential Injection

`ManagedToolExecutor` receives an immutable `ManagedExecutionContext` containing Runtime access and a
credential resolver. For the selected Tool:

1. validate user arguments and create the credential-free request
2. create the audit start row
3. acquire the distributed rate limit
4. resolve exactly the Tool's credential slots
5. format each secret according to its credential kind
6. inject credentials into header or query targets after user bindings
7. call provider-egress and normalize the response
8. complete the audit record once
9. clear mutable secret buffers

Credential targets are checked against Runtime Metadata again at execution. User arguments can never override
a credential target. Header comparison is case-insensitive, query comparison is case-sensitive, and
trace/forwarding/hop-by-hop headers remain reserved. A credential value is never placed in an exception,
metric tag, span attribute, log field, MCP error, or audit row.

## 9. Stateless and Multi-Replica Transport

The runtime uses MCP Java SDK `WebMvcStatelessServerTransport` and `McpServer.sync(McpStatelessServerTransport)`.
The MCP adapter gains a stateless Tool-specification emitter while preserving its stateful API for existing
consumers.

No `Mcp-Session-Id` or in-memory session state is required. Any healthy runtime replica can process the next
request for the same runtime/grant. Local handle caching remains an optimization only; cache misses rebuild
the same immutable grant-scoped server from PostgreSQL and Catalog metadata.

The hosted Compose contract supports `docker compose up --scale runtime=2`. Nginx resolves the `runtime`
service and may route consecutive requests to different replicas. Shared correctness state consists only of
PostgreSQL runtime/grant/binding/rate/audit rows and immutable Tool Catalog data.

## 10. Control-Plane API

All endpoints require hosted OIDC authentication and same-origin CSRF protection.

```text
POST /api/credentials
GET  /api/credentials
GET  /api/credentials/{credentialId}
POST /api/credentials/{credentialId}/rotation
POST /api/credentials/{credentialId}/revocation

POST /api/tool-catalogs/{catalogId}/runtimes
GET  /api/runtimes/{runtimeId}
POST /api/runtimes/{runtimeId}/revocation

POST /api/runtimes/{runtimeId}/grants
GET  /api/runtimes/{runtimeId}/grants
POST /api/runtimes/{runtimeId}/grants/{grantId}/revocation

GET  /api/runtimes/{runtimeId}/audit?limit=50&cursor=<opaque>
```

The activation body gains `credentialBindings`. Existing credential-free requests remain valid. Credential
and grant create/rotate responses set `Cache-Control: no-store`. Plaintext credential input and one-time grant
tokens are never included in GET responses.

## 11. Persistence

Migration `V6__managed_runtime_policy.sql` adds:

- `managed_credential`
- `managed_runtime_credential_binding`
- `managed_runtime_grant`
- `managed_runtime_rate_window`
- `managed_tool_execution_audit`

Every owner-scoped foreign key includes `owner_account_id`. Runtime/grant and runtime/binding foreign keys use
composite unique identities to prevent cross-owner association in the database. Credential rows are mutable
only through versioned rotation and revocation operations; bindings and grant Tool sets are immutable.

No secret-bearing column is indexed or returned by general Catalog/resource queries. Audit retention defaults
to 30 days and is documented as an operator cleanup responsibility in this slice.

## 12. Failure Semantics

| failure | behavior |
|---|---|
| invalid credential input or binding | fixed 400; no runtime persisted |
| absent or foreign credential/runtime/grant | fixed 404 or 401 at the established boundary |
| credential revoked or decrypt failure during call | fixed internal MCP error; no provider request |
| Tool outside grant visibility | MCP unknown Tool response; no rate/audit/provider work |
| rate limit exhausted | safe `RATE_LIMITED` Tool error and audit row |
| audit start failure | fixed internal MCP error; no provider request |
| audit completion failure | fixed internal MCP error; provider call not retried |
| PostgreSQL unavailable during authentication | 503 fixed runtime-unavailable response |
| malformed or secret-colliding metadata | activation fails before persistence |
| fatal `Error` | original identity rethrown after bounded cleanup |
| thread interruption | interrupt flag restored; fixed internal outcome where response is possible |

## 13. Verification Strategy

### 13.1 Domain and application

- credential value/kind/bounds and defensive-copy contracts
- exact slot coverage and kind/target compatibility
- owner and scoped grant lifecycle, expiry, revocation, allowed Tool set
- user argument and credential target collision rejection
- OPAQUE query/header, Bearer, and Basic wire formatting
- no credential resolution for hidden or rate-limited Tools
- audit state transition and fixed failure precedence

### 13.2 Cryptography and persistence

- envelope round trip and AAD mutation rejection
- key rotation and retired-key read support
- key-file path, symlink, size, and permission policy
- PostgreSQL owner FKs, atomic activation, rotation, revocation, rate-window concurrency, audit CAS
- migration from V1 through V6 on PostgreSQL 17.9-alpine

### 13.3 HTTP and MCP

- owner APIs never return secret material
- scoped `tools/list` exact schema and `tools/call` authorization
- exact provider query/header/Bearer/Basic request with one upstream call
- rate limit shared across two runtime application instances
- audit result excludes arguments, credential data, raw provider data, paths, and stack traces
- stateless initialize/list/call requests alternate between replicas without session affinity
- runtime/grant/credential revocation takes effect on the next request

### 13.4 Acceptance

- affected module tests and hosted configuration contract
- Linux/Windows core CI compatibility
- full repository `clean test integrationTest :apps:cli:installDist`
- `git diff --check`
- leak scans for fixture secrets, bearer tokens, key paths, raw provider markers, and stack traces

## 14. Completion Boundary

This slice is complete when credential-bearing Catalogs can be activated and safely called, scoped grants see
and execute only their allowed Tools, rate and audit behavior is shared across two replicas, and no request
requires session affinity. It does not claim public Catalog sharing, OAuth2 token acquisition, cross-Catalog
Gateway routing, billing, or Catalog version migration.

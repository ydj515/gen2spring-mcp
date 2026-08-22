# Catalog Version Diff and Runtime Migration Design

- Status: approved
- Date: 2026-08-22
- Related specifications:
  - `docs/prd.md`
  - `docs/superpowers/specs/2026-08-21-runtime-metadata-tool-catalog-design.md`
  - `docs/superpowers/specs/2026-08-21-managed-mcp-runtime-design.md`
  - `docs/superpowers/specs/2026-08-21-managed-runtime-credential-policy-scale-design.md`

## 1. Purpose

Add a deterministic API-change diff and a safe lifecycle for moving an active single-Catalog Managed Runtime
between immutable Tool Catalog revisions. Catalog publication remains generation-driven, runtime migration is an
explicit owner action, and existing MCP bearer tokens and scoped grants remain valid only when the target contract
is proven compatible.

This feature does not turn the Managed Runtime into a public or cross-Catalog Gateway.

## 2. Goals

- Group explicitly related immutable Catalogs into a linear family with monotonically increasing revisions.
- Let a generation request name the exact predecessor Catalog instead of inferring identity from labels, URLs, or
  checksums.
- Produce a deterministic, owner-scoped diff keyed by the public MCP `toolName`.
- Classify every change as `COMPATIBLE` or `BREAKING` using a conservative policy.
- Move an active Runtime with compare-and-set protection while preserving its token, grants, expiry, provider
  override, credential bindings, rate state, and execution history.
- Retain an append-only transition history and allow a bounded rollback to the immediately preceding Catalog.
- Keep publication, migration, and rollback fail-closed and transactionally consistent in PostgreSQL.

## 3. Non-goals

- Catalog branching, merge commits, labels such as semantic versions, or automatic latest-version promotion.
- Inferring a family from a specification filename, import URL, OpenAPI `info.version`, or source checksum.
- Mutating an existing Catalog or Runtime Metadata document.
- Cross-owner Catalog sharing, cross-Catalog discovery, Gateway routing, OAuth2 acquisition, or billing.
- Migrating generated source projects in place.
- A new hosted Catalog-management dashboard.

## 4. Catalog Family Model

PostgreSQL migration V7 adds `tool_catalog_family` and extends Catalog and generation state.

`tool_catalog_family` owns:

- `id`
- `owner_account_id`
- current `head_catalog_id`
- `created_at` and `updated_at`

Each `tool_catalog` owns:

- `family_id`
- positive `revision`
- optional `predecessor_catalog_id`

Each generation job stores an optional trusted `predecessor_catalog_id` column. The submitted value participates in
the idempotency request hash but is not recovered from arbitrary worker JSON during publication.

Existing Catalogs are backfilled as independent families at revision 1. Their family ID is their existing Catalog
ID, their predecessor is absent, and they become the head of that family. A generation with no predecessor creates
a new family. A generation with a predecessor must belong to the same owner and can publish only if that predecessor
is still the family head.

The database enforces owner-scoped composite foreign keys, unique `(family_id, revision)`, and a unique Catalog
identity suitable for same-owner and same-family predecessor references. Application publication locks the family
row before validating the head and allocating `head revision + 1`.

Catalog families are linear. If two jobs name the same predecessor, the first valid completion advances the head.
The second completion is rejected with `CATALOG_LINEAGE_CONFLICT`; its success transaction publishes no Catalog or
artifacts, and the worker records a safe terminal job failure.

## 5. Generation and Publication Flow

`POST /api/jobs` accepts optional `predecessorCatalogId` in addition to `specificationId` and `configuration`.

At submission:

1. authenticate the hosted owner and validate the predecessor UUID;
2. hide cross-owner and absent predecessor rows behind the same not-found response;
3. bind the predecessor to the generation job and include it in the request hash;
4. preserve existing replay and idempotency-conflict rules.

At successful worker completion:

1. decode and validate deterministic Runtime Metadata as today;
2. lock the predecessor family when one was requested;
3. require the predecessor to remain the current head;
4. allocate the next revision and atomically insert Catalog, entries, artifacts, and family-head update;
5. leave every row unchanged if any validation or insert fails.

The worker may convert only the explicit lineage conflict into a fixed safe failed completion. Infrastructure,
codec, interruption, and fatal failures retain their existing retry and error boundaries.

## 6. Deterministic Catalog Diff

The diff service reads two immutable Catalogs belonging to the authenticated owner and the same family. It builds
sorted maps keyed by `toolName`, compares canonical Runtime Metadata values, and returns changes sorted first by Tool
name and then by change kind. It never stores a derived diff.

The response contains:

- source and target Catalog ID, revision, metadata checksum, and specification checksum;
- overall `COMPATIBLE` or `BREAKING` classification;
- added, removed, and modified Tool entries;
- fixed field paths and change categories without secret values, arguments, provider payloads, or local paths.

The initial compatibility matrix is deliberately conservative:

- Tool addition: compatible.
- Tool removal or rename: breaking.
- Description-only change: compatible.
- Any existing Tool input schema change: breaking.
- Optional output-property addition: compatible.
- Output removal, required-set change, or output type/constraint change: breaking.
- HTTP method, base URL, path, parameter binding, timeout, retry, pagination, or normalization change: breaking.
- Credential slot, required flag, target location, or target name change: breaking.

Canonical object-key ordering, schema property ordering, and other serialization-only differences are not changes.
An invalid or unrecognized metadata shape fails closed rather than being classified as compatible.

## 7. Runtime Migration

`POST /api/runtimes/{runtimeId}/migrations` accepts:

- `expectedCurrentCatalogId`
- `targetCatalogId`
- `targetChecksum`

The service requires an active owner-owned Runtime, a same-family target, a matching current Catalog and target
checksum, a compatible deterministic diff, and an identical credential-slot contract. PostgreSQL then performs one
compare-and-set update of the Runtime Catalog ID and checksum and appends one transition row in the same transaction.

Migration preserves:

- Runtime ID and endpoint;
- owner and additional bearer tokens;
- grant Tool sets and rate limits;
- provider base URL;
- credential bindings and captured credential versions;
- creation time, expiry, revocation state, rate buckets, and execution audit rows.

No grant automatically gains an added Tool. The owner activation token continues to represent full current-Catalog
visibility, while existing scoped grants retain their explicit Tool sets. The Runtime handle cache already includes
the Catalog checksum in its identity, so the next authenticated request materializes the new registry without sticky
session state.

## 8. Transition History and Rollback

V7 adds append-only `managed_runtime_catalog_transition` rows containing a per-Runtime sequence, source and target
Catalog/checksum, transition kind, diff checksum, and timestamp. It stores no bearer, credential, argument, response,
or path data.

`GET /api/runtimes/{runtimeId}/migrations` returns a bounded owner-scoped page in descending sequence order.

`POST /api/runtimes/{runtimeId}/rollback` restores only the source Catalog of the most recent non-reverted
transition and uses the current Catalog as a compare-and-set precondition. Rollback is blocked when any active scoped
grant names a Tool absent from the source Catalog or when the source credential-slot contract no longer matches.
Rollback appends a new transition row; it never deletes or rewrites history.

## 9. HTTP and Error Contract

- `GET /api/tool-catalogs/{catalogId}/diff?targetCatalogId=...`
- `POST /api/runtimes/{runtimeId}/migrations`
- `GET /api/runtimes/{runtimeId}/migrations`
- `POST /api/runtimes/{runtimeId}/rollback`

All endpoints require the existing hosted OIDC session and same-origin CSRF policy where applicable.

- malformed input: `400`
- absent or cross-owner resource: `404` without existence disclosure
- stale family head or Runtime compare-and-set: `409 CATALOG_VERSION_CONFLICT`
- breaking target: `409 CATALOG_MIGRATION_BREAKING`
- grant or credential incompatibility: `409 CATALOG_MIGRATION_BLOCKED`
- persistence or codec failure: `503` with no partial state change

Messages remain fixed and do not include metadata documents or user-controlled values.

## 10. Complexity and Bounds

For Tool count `T` and total canonical schema/metadata size `S`, diff construction takes `O(T + S)` time and
`O(T + S)` memory. Catalog and Runtime lookups use indexed `O(log R)` database access. Public list and transition
history limits remain in the range 1 to 100, and Catalog Tool count remains bounded at 1,000.

## 11. Acceptance Criteria

- V1-to-V7 migration backfills every existing Catalog into a valid independent family.
- Explicit predecessor publication produces deterministic revisions and rejects stale concurrent heads.
- Diff responses are stable across insertion and JSON object-key order.
- Every compatibility-matrix row has independent positive and negative tests.
- Cross-owner diff, predecessor, migration, history, and rollback requests are indistinguishable from absence.
- Compatible migration preserves Runtime ID, tokens, grants, credential versions, expiry, rate state, and audits.
- A breaking or stale migration changes no Runtime, binding, transition, or cache-visible state.
- Two Runtime replicas observe the migrated checksum without affinity and expose the target Tool registry.
- Rollback restores the immediately preceding Catalog and rejects incompatible active grants.
- README, PRD, user guide, hosted deployment guide, and Managed Runtime HTML describe the completed boundary without
  implying public Gateway behavior.

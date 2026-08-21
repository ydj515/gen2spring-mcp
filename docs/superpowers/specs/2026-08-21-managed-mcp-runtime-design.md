# Managed MCP Runtime v1 Design

## 1. Purpose

This slice turns an immutable owner-scoped Tool Catalog into a dynamically registered MCP server without
generating or running a downloaded Spring project. It adds the first deployable Managed MCP Runtime data
plane and keeps MCP Gateway concerns explicitly separate.

The runtime consumes the canonical `RUNTIME_METADATA.json` contract completed in the previous slice. It
does not reconstruct `ToolDefinition` from OpenAPI and does not depend on a Spring AI generator profile.

## 2. Scope

Included:

- owner activation and revocation of an immutable Tool Catalog as a runtime instance
- one-time catalog-scoped bearer token issuance with digest-only persistence
- a separate `apps/runtime` data-plane application
- a catalog-backed dynamic MCP Java SDK registry
- exact `tools/list` and bounded `tools/call` over Streamable HTTP
- the same parameter binding, timeout, retry, pagination, response-size, and normalization semantics encoded
  in Runtime Metadata
- provider-relative base URL resolution at activation time
- fail-closed rejection of credential-requiring Catalogs
- an isolated provider-egress boundary that prevents the runtime from directly reaching arbitrary networks
- PostgreSQL persistence, deployment wiring, metrics, traces, safe logs, and regression tests

Excluded:

- any change to downloaded Spring AI 1 or Spring AI 2 projects
- an MCP Java SDK downloadable project target
- Catalog aggregation, cross-Catalog routing, sharing, or discovery
- per-user or per-Tool authorization policy
- credential value storage, credential routing, or secret injection
- Gateway rate limiting, execution audit history, billing, or policy evaluation
- stateless Streamable HTTP and multi-replica runtime sessions
- mutable Catalogs or automatic rebinding to a newer Catalog

The excluded routing and policy features remain future MCP Gateway work. The service in this design is a
single-Catalog execution runtime, not a Gateway.

## 3. Architectural Decision

Managed Runtime is deployed as a separate application rather than embedded in `apps/web`.

```text
Control plane

Browser --OIDC session/CSRF--> apps/web
                                  |
                                  +--> runtime instance rows --> PostgreSQL
                                  +--> immutable Tool Catalog --> PostgreSQL

Data plane

MCP client --Bearer--> apps/runtime --catalog lookup--> PostgreSQL
                          |
                          +--> McpJavaSdkEmitter --> dynamic Tool registry
                          |
                          +--> provider-egress --validated public egress--> provider API
```

`apps/web` owns activation lifecycle and one-time token delivery. `apps/runtime` owns MCP protocol sessions,
registry materialization, and bounded Tool execution. The provider-egress process owns DNS resolution,
address policy, and connect-time network enforcement. The runtime has no direct external egress, and the
provider-egress process has no database, OIDC, object-storage, or runtime-token access.

The initial deployment is a single runtime replica. A later stateless Streamable HTTP slice may replace the
session boundary without changing Catalog, instance, or Tool execution contracts.

## 4. Emitter Boundaries

The existing `ToolEmitter` application port remains a source-generation port:

```text
ToolDefinition
  +-- SpringAi1ToolEmitter --> generated source files
  +-- SpringAi2ToolEmitter --> generated source files
  +-- RuntimeMetadataDocumentFactory --> RUNTIME_METADATA.json
```

Managed Runtime starts after persisted Runtime Metadata:

```text
RuntimeMetadataDocument.RuntimeTool
  --> McpJavaSdkEmitter
  --> MCP Java SDK Tool specification + call handler
```

`McpJavaSdkEmitter` therefore does not implement `ToolEmitter`, does not enter `ProjectGeneratorRegistry`, and
does not write files. It implements a new Managed Runtime adapter port that accepts immutable `RuntimeTool`
values plus an application-owned call handler. This prevents source-generation types and MCP SDK types from
leaking into the domain.

## 5. Module and Application Boundaries

### 5.1 Domain

The domain continues to own framework-neutral Runtime Metadata. It adds only runtime instance identities and
state values that have no Spring, Jackson, JDBC, or MCP SDK dependency.

### 5.2 Application

The application module owns:

- runtime instance activation and revocation rules
- bearer token generation input/output boundaries and token digest verification contract
- Catalog activation validation
- dynamic registry materialization and cache policy
- Tool lookup, argument validation orchestration, and execution result mapping
- provider-call and runtime-instance persistence ports

The application layer never receives a plaintext token after request authentication and never logs caller
arguments, provider bodies, or URLs.

### 5.3 Adapters

- `modules/adapters/mcp-java-sdk`: MCP Java SDK Tool specification and handler conversion
- `modules/adapters/persistence-postgres`: runtime instance persistence and Catalog loading
- `modules/adapters/provider-egress`: mTLS client and provider response transport contract
- existing Runtime Metadata codec: exact persisted Catalog decoding and checksum verification

### 5.4 Applications

- `apps/web`: owner-scoped activation, status, and revocation API
- `apps/runtime`: bearer authentication, Streamable HTTP endpoint, SDK server, registry cache, telemetry
- `apps/provider-egress`: internal mTLS endpoint and SSRF-safe outbound HTTP execution

`apps/provider-egress` is a network security boundary, not an MCP Gateway. It does not understand Catalogs,
Tools, owners, MCP messages, or authorization policy.

## 6. Runtime Instance Model

The next Flyway migration adds `managed_runtime_instance`:

- `id` UUID primary key
- `owner_account_id` UUID foreign key
- `tool_catalog_id` UUID foreign key
- `token_digest` fixed-length binary HMAC-SHA-256 output
- optional normalized `provider_base_url` for metadata whose base URL is provider-relative
- `created_at`, `expires_at`, nullable `revoked_at`

The row is immutable except for the one-way `revoked_at` transition. Runtime state is derived as `ACTIVE`,
`EXPIRED`, or `REVOKED`; terminal states never reopen. Deleting or mutating the referenced Catalog is not
supported.

One Catalog may have multiple independent runtime instances. Each instance is permanently pinned to the
Catalog metadata checksum present at activation.

## 7. Activation and Control-plane API

Hosted OIDC owners use same-origin, CSRF-protected APIs:

```text
POST /api/tool-catalogs/{catalogId}/runtimes
GET  /api/runtimes/{runtimeId}
POST /api/runtimes/{runtimeId}/revocation
```

Activation returns the runtime ID, MCP endpoint, expiry, and a cryptographically random bearer token exactly
once. Subsequent reads never return the token or its digest. The token uses at least 256 bits of entropy and
is stored only as `HMAC-SHA-256(operator-pepper, token)`; comparison is constant-time.

The default lifetime is 24 hours. Operators may configure a shorter value or a value up to 30 days. There is
no refresh API in v1; an owner creates a replacement runtime and revokes the old one.

Activation fails before persistence when:

- the Catalog does not belong to the authenticated owner
- Runtime Metadata checksum or version verification fails
- no Tool is present, Tool names are not unique, or bounds exceed the Runtime Metadata contract
- any Tool declares a credential slot
- an absolute provider base URL violates the provider scheme, authority, or port policy
- a provider-relative base URL has no explicit activation `providerBaseUrl`
- the supplied base URL has userinfo, query, fragment, unsupported scheme or port, or a private/reserved target

Ownership failures do not distinguish another owner's resource from a missing resource.

## 8. Data-plane Authentication and Endpoint

The MCP endpoint is:

```text
POST /mcp/{runtimeId}
GET  /mcp/{runtimeId}
DELETE /mcp/{runtimeId}
```

The transport follows the pinned MCP Java SDK Streamable HTTP contract. Each request requires
`Authorization: Bearer <runtime-token>`. Browser sessions and OIDC cookies are not accepted on the data plane;
CSRF is not used for bearer-authenticated MCP requests.

The runtime loads the instance by opaque UUID, rejects missing, malformed, expired, or revoked instances with
the same fixed 401 response, then verifies the token digest. Tokens, digests, request arguments, provider
URLs, response bodies, and MCP session IDs are excluded from logs, metrics, traces, and errors.

Revocation takes effect on the next MCP request. Authentication is checked before a cached registry is used,
so registry caching cannot bypass expiry or revocation.

## 9. Dynamic Registry and `McpJavaSdkEmitter`

Registry materialization verifies the persisted Catalog checksum, decodes Runtime Metadata, sorts Tools by
canonical name, and builds an immutable name-indexed map. Work is `O(T)` in Tool count; Tool lookup is `O(1)`.

MCP Java SDK 0.18.3 binds a Tool list and a fixed WebMVC endpoint to a server/transport instance. The runtime
therefore never mutates one global `McpSyncServer` with tenant-specific Tools. Each active runtime instance
materializes an isolated server handle containing:

- one `WebMvcStreamableServerTransportProvider` fixed to `/mcp/{runtimeId}`
- one `McpSyncServer` built once from that instance's immutable Tool specifications
- that transport provider's `RouterFunction`
- the pinned Catalog checksum and bounded lifecycle metadata

A single application-owned delegating router authenticates the request, resolves the exact runtime handle,
and invokes only that handle's router function. SDK server `addTool` and `removeTool` are not used after build.
Expiry and revocation close the server and transport gracefully, terminate their sessions within a fixed timeout,
and then remove the handle. Capacity never evicts an active handle: a new runtime receives one fixed HTTP 503 until
an expired, revoked, or explicitly closed handle frees capacity. A handle is never shared across runtime IDs even
when two instances reference the same Catalog.

The emitter maps each Runtime Tool to one MCP Java SDK specification containing:

- exact name and description
- exact canonical input schema
- exact normalized output schema and successful `structuredContent` when the schema is present
- a handler bound to the immutable runtime ID, Catalog checksum, and Tool name

The handler does not capture bearer tokens, account identities, mutable database objects, or caller argument
maps beyond the call lifetime.

The server-handle cache key is `(runtimeId, catalogChecksum)`. It is bounded by entry count and time, uses
single-flight materialization, and never admits a replacement by closing a live session handle. Failed builds are
not cached, while failed cleanup remains tracked and consumes capacity until a later cleanup succeeds. Runtime
instance authentication still reaches the store for every request in v1.

`tools/list` returns every Tool in the activated Catalog exactly once. Because credential-bearing Catalogs are
rejected during activation, a listed Tool is never knowingly uncallable due to missing credentials.

## 10. Tool Execution

`tools/call` applies the same logical stages as generated runtimes:

1. MCP SDK input-schema validation
2. exact Tool lookup
3. argument normalization and HTTP parameter binding
4. bounded provider request through provider-egress
5. status-first provider failure classification
6. response media and size validation
7. response normalization and canonical MCP result conversion
8. bounded retry and pagination only when encoded in Runtime Metadata

The managed executor is implementation code, not generated source. Behavior is characterized against the
existing Spring AI 1 and Spring AI 2 generated-runtime contract with shared independent fixtures rather than
copying expectations from either renderer.

Fatal `Error` values propagate. Provider failures become safe `isError=true` Tool results. Unexpected internal
failures become fixed JSON-RPC internal errors with type-only diagnostics. Late provider results cannot mutate
an already timed-out or cancelled MCP result.

Runtime work is executed on a bounded executor with a bounded queue. Default limits are operator-configurable
and fail startup when invalid. Queue saturation fails before provider egress and does not create unbounded
threads or futures.

## 11. Provider Egress and SSRF Boundary

`apps/runtime` has no direct external network route. It sends a bounded internal request over mTLS to
`apps/provider-egress`, which performs DNS resolution, address classification, and the outbound connection in
one boundary.

Provider-egress v1 policy:

- HTTP and HTTPS only
- ports 80 and 443 only
- no userinfo, fragment, or base URL query
- deny loopback, private, link-local, multicast, reserved, IPv6 ULA, and cloud metadata targets
- no redirects
- DNS results and the actual connected address must both pass policy
- strip hop-by-hop and runtime-reserved headers; set `Host` from the validated target
- bounded method, path, query, header count/size, request body, response headers, and response body
- fixed connect and total timeout controlled by the stricter Runtime Metadata/operator bound
- no retry inside provider-egress; application retry policy remains the single retry owner

The mTLS request is authenticated as the runtime service, but it carries no runtime bearer token, account ID,
or Catalog ID. Provider-egress returns only bounded status, allow-listed response headers, and body bytes.

Private or on-premise provider access is deferred until an operator-managed destination allow-list and network
segmentation contract is designed. It must not be enabled by a request parameter.

## 12. Error and Security Contracts

Control-plane failures use fixed safe codes and stages without echoing IDs, URLs, tokens, paths, or database
details. Data-plane authentication failures are equivalent. MCP execution preserves the existing provider vs
internal error boundary.

Security invariants:

- runtime tokens are returned once, never stored in plaintext, and never transported to provider-egress
- Runtime Metadata is checksum-verified before registry construction
- owner checks are mandatory for create/read/revoke control-plane operations
- runtime tokens authorize exactly one immutable runtime instance and one Catalog
- credential-bearing Catalogs fail activation rather than list partially executable Tools
- direct platform-network and external-network access cannot coexist in provider-egress
- runtime arguments and provider content never enter durable audit/event tables in v1
- all body, header, Tool-count, executor, retry, pagination, session, and cache bounds are finite

## 13. Observability

Managed Runtime emits bounded-cardinality metrics and traces using the existing canonical observability
vocabulary where semantics match. Tags include outcome, error category, HTTP status class, and fixed runtime
component values; they exclude runtime ID, Catalog ID, owner, Tool arguments, URL, hostname, and session ID.

Tool name and operation ID may appear only on traces under the same bounded source-name contract already used
by generated runtimes. Logs are type-only and correlation-safe. Provider response size, execution duration,
queue depth, active sessions, registry cache size, and authentication outcomes are measurable without storing
caller payloads.

Execution audit history remains out of scope and must not be implied by metrics or logs.

## 14. Deployment

Hosted Compose adds runtime and provider-egress services:

- proxy routes `/mcp/` only to `apps/runtime`
- runtime joins the proxy, runtime-control, and provider-call networks but no external egress network
- provider-egress joins only the provider-call and egress networks
- runtime receives PostgreSQL credentials, token-HMAC pepper, and mTLS client material only
- provider-egress receives mTLS server material only
- neither service receives OIDC client secrets, MinIO credentials, Docker socket, or worker workspace
- both run as numeric non-root users with read-only roots, bounded tmpfs, dropped capabilities, and
  `no-new-privileges`

The proxy removes caller-supplied forwarding headers and never logs Authorization values. Runtime health is
separate from provider reachability; a provider outage must not make the service readiness endpoint perform
external network calls.

## 15. Validation Strategy

Implementation follows TDD and includes:

- domain tests for instance identity, lifecycle, expiry, and immutable binding
- application tests for activation rejection, ownership, revocation, token digest, cache bounds, and error
  equivalence
- independent MCP Java SDK adapter tests for exact schema/name/description mapping
- executor contract tests shared semantically with both generated-runtime families
- PostgreSQL 17.9 integration tests for owner isolation, terminal revocation, and concurrent activation
- provider-egress tests for DNS rebinding, private/reserved addresses, redirects, header stripping, size bounds,
  timeout, malformed responses, and cleanup
- raw MCP client tests for initialize, `tools/list`, successful `tools/call`, provider failure, invalid input,
  internal failure, expiry, and revocation
- hosted end-to-end test: OIDC owner activation, one-time token, independent mock provider, exact one upstream
  request, normalized MCP result, revocation, and post-revocation denial
- leak scans for bearer token, token digest, owner identity, URL, arguments, provider content, and stack traces
- deployment contract tests for network separation, secret scope, numeric users, read-only roots, and proxy
  routing

The full repository acceptance command continues to run with explicit Java 17 and Java 21 homes. PRD and
README status change only after the end-to-end Managed Runtime slice passes; they must not claim Gateway,
credential routing, stateless transport, or multi-replica completion.

## 16. Complexity and Limits

- registry construction: `O(T)` time and `O(T + S)` memory for Tool count and schema size
- per-call Tool lookup: expected `O(1)`
- input validation and canonical conversion: `O(A)` in bounded argument size
- request/response handling: `O(B)` in bounded body size
- paginated execution: `O(P * B)` with a fixed maximum page count
- runtime authentication store lookup: indexed `O(log R)` before constant-time digest comparison

No path allocates memory proportional to an unbounded caller stream, provider response, Tool count, retry
count, page count, session count, executor queue, or registry cache.

## 17. Deferred Follow-up

After this slice, the next independent decisions are:

1. credential storage and routing for secret-requiring Tools
2. private/on-premise provider destination allow-lists
3. stateless Streamable HTTP and multi-replica runtime sessions
4. execution audit retention and owner-visible history
5. MCP Gateway aggregation, sharing, authorization policy, and rate limiting
6. optional MCP Java SDK downloadable project profile

None of these is required to preserve the contracts introduced by Managed Runtime v1.

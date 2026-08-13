# OpenAPI 3.1 and Guided Endpoint Editor Design

- Status: approved
- Date: 2026-08-14
- Related specifications:
  - `docs/superpowers/specs/2026-08-07-openapi-mcp-generator-p0-design.md`
  - `docs/superpowers/specs/2026-08-11-local-operation-editor-design.md`
  - `docs/superpowers/specs/2026-08-12-spring-boot-thymeleaf-web-migration-design.md`
  - `docs/superpowers/specs/2026-08-13-hosted-generation-platform-design.md`

## 1. Purpose

Extend the canonical OpenAPI analyzer from OpenAPI 3.0.x to a bounded OpenAPI 3.0.x and 3.1.x subset, then simplify the Web editor around the primary user journey:

1. upload an OpenAPI document;
2. understand which endpoints can become MCP Tools and select them;
3. configure only the necessary project and Tool settings before generation.

The analyzer, CLI, local Web mode, and hosted Web mode must share one support decision. The browser must not reinterpret OpenAPI semantics or independently decide whether an endpoint is supported.

The repository-root `swagger-3.0.yml` and `swagger-3.1.yml` files are the primary paired acceptance documents for this work. They describe the same API using the two OpenAPI versions and must produce equivalent canonical operation decisions after normalization.

This design supersedes the OpenAPI 3.0-only version boundary in the P0 design and the five-step information architecture in the local editor design. It does not supersede their generation, validation, security, process cleanup, or artifact safety contracts.

## 2. Problem Statement

The current analyzer rejects every OpenAPI 3.1 document at the document version gate. It also treats every warning as an unsupported operation and only accepts a body-bearing success response when its single media type is exactly `application/json`. The paired root samples declare their success bodies under `*/*`, so the current analyzer cannot expose useful selectable endpoints from either sample.

The current editor shows five sections at once and exposes Tool metadata, parameter sources, output, retry, pagination, response normalization, preview, and generation details before the user has selected an endpoint. Upload is a file input plus a separate analyze button, and the interface does not present a focused drag-and-drop state or a clear completed-file state.

Hosted mode persists specifications and jobs, but its dashboard still requires raw generation configuration JSON. The new editor structure therefore needs a shared product model even where the local and hosted persistence and job lifecycle remain different.

## 3. Goals

- Accept OpenAPI 3.0.x and OpenAPI 3.1.x documents through the same analyzer port.
- Normalize the approved OpenAPI 3.1 schema subset into the existing version-independent domain model.
- Preserve every endpoint in analysis output, including unsupported endpoints and fixed reasons.
- Distinguish supported, supported-with-warning, and unsupported endpoints.
- Narrowly infer a JSON candidate from a schema-bearing `*/*` success response.
- Keep runtime response media validation fail-closed.
- Replace the five-section editor with an approved three-step guided flow.
- Make file selection and drag-and-drop fully interactive and accessible.
- Use progressive disclosure for project and Tool policy settings.
- Keep local and hosted Web modes aligned on the same analysis and editor contracts.
- Verify CLI and Web behavior with the paired root sample documents.

## 4. Non-goals

- Full JSON Schema 2020-12 implementation.
- Automatic support for `oneOf`, `anyOf`, discriminators, recursive schemas, or arbitrary multi-type unions.
- Inferring binary or text responses as JSON.
- Wildcard request-body media type inference.
- Browser-side OpenAPI parsing or support classification.
- Bulk editing the same property across many Tools in the first iteration.
- Replacing Thymeleaf and the existing ECMAScript modules with a frontend framework.
- Changing generation profile versions, emitter behavior, validation stages, persistence, job queue, artifact storage, or sandbox policy.
- Redesigning specification history and job history beyond linking them into the guided editor.

## 5. Considered UX Approaches

### 5.1 Selected: guided three-step flow

The editor presents upload, endpoint selection, and configuration/generation as three explicit steps. A user sees only the information needed for the current decision. This was selected because it provides the clearest progression for users who do not already understand the generator configuration model.

### 5.2 Rejected: one-page progressive sections

Keeping every section on one page would minimize navigation changes but would preserve the current visual density and make completion state difficult to understand.

### 5.3 Rejected: permanent split-pane endpoint workspace

A persistent endpoint list beside a detailed editor helps experienced users edit many operations quickly, but it makes upload and first-time project setup secondary. It may be reconsidered if usage data shows repeated editing of dozens of endpoints in one session.

## 6. Canonical Analysis Contract

### 6.1 Operation support decision

Analysis owns a typed operation decision with these states:

- `SUPPORTED`: the operation can be selected without an analysis warning;
- `SUPPORTED_WITH_WARNING`: the operation can be selected, but the UI and final summary must retain one or more warnings;
- `UNSUPPORTED`: the operation remains visible but cannot be selected.

Each analyzed operation contains:

- HTTP method and path;
- operation ID, summary, and description when present;
- canonical parameters and response schema metadata already needed by preview;
- support status;
- a deterministically ordered list of typed issues.

Each issue contains a stable code, `WARNING` or `ERROR` severity, and a fixed safe message. Raw parser messages, filesystem paths, document fragments, header values, server URLs, secret candidates, and stack traces must not appear in the issue message.

The transport output retains the existing boolean `supported` value for compatibility during this change, but derives it from `status != UNSUPPORTED`. It is not an independent source of truth.

The operation issue code set for this change is:

- `OPERATION_ID_MISSING`;
- `OPERATION_ID_DUPLICATED`;
- `HTTP_METHOD_UNSUPPORTED`;
- `RECURSIVE_SCHEMA_UNSUPPORTED`;
- `SCHEMA_MISSING`;
- `SCHEMA_TYPE_UNSUPPORTED`;
- `SCHEMA_NULLABILITY_UNSUPPORTED`;
- `SCHEMA_COMPOSITION_UNSUPPORTED`;
- `SCHEMA_MULTI_TYPE_UNSUPPORTED`;
- `SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED`;
- `SCHEMA_CONSTRAINT_UNSUPPORTED`;
- `SCHEMA_NESTED_UNSUPPORTED`;
- `PARAMETER_LOCATION_UNSUPPORTED`;
- `PARAMETER_SERIALIZATION_UNSUPPORTED`;
- `GET_REQUEST_BODY_UNSUPPORTED`;
- `REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED`;
- `SUCCESS_MEDIA_TYPE_INFERRED`;
- `SUCCESS_MEDIA_TYPE_UNSUPPORTED`;
- `SUCCESS_SCHEMA_UNSUPPORTED`;
- `SECURITY_REQUIREMENT_UNSUPPORTED`.

Messages are finite, tested, and shared by CLI and Web serialization. Adding or merging an issue code requires updating the canonical issue contract and its independent expectations rather than falling back to parser text.

### 6.2 Document-level failures

The analyzer rejects the complete upload before producing an operation list when any of these conditions applies:

- malformed YAML or JSON;
- missing OpenAPI version;
- a version outside 3.0.x and 3.1.x;
- an OpenAPI 3.1 document that declares a non-default JSON Schema dialect the analyzer does not implement;
- unsafe external references or an unresolved document boundary;
- the existing source size, extension, or path boundary is violated.

Document failures keep the existing fixed generator error envelope. An unsupported feature confined to one endpoint does not reject unrelated endpoints.

The analyzer enumerates every OpenAPI operation type. The current emitter-supported method set remains `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`; other methods remain visible as `UNSUPPORTED` with `HTTP_METHOD_UNSUPPORTED`. Missing and duplicate operation IDs are also operation-level issues so unrelated endpoints remain selectable. Every operation sharing a duplicate ID receives `OPERATION_ID_DUPLICATED`.

## 7. OpenAPI 3.1 Normalization Boundary

### 7.1 Version handling

The version gate accepts only the exact numeric-patch forms `3.0.<digits>` and `3.1.<digits>`. It does not accept Swagger 2, prerelease-like suffixes, future OpenAPI versions, or arbitrary strings that merely contain those tokens. An OpenAPI 3.1 `jsonSchemaDialect` is either absent or exactly the OAS 3.1 base dialect URI; any other dialect is a document-level unsupported-version failure.

The source `openapi` value is retained in analysis output so the UI can show the exact uploaded version.

### 7.2 Nullable schemas

OpenAPI 3.0 `nullable: true` and OpenAPI 3.1 `type: [T, "null"]` normalize to the same canonical nullable schema when the existing generator contract permits nullability.

For the 3.1 type set:

- exactly one supported non-null type plus `null` is the approved nullable union;
- exactly one supported non-null type without `null` is the ordinary schema;
- zero non-null types or more than one non-null type is unsupported;
- the scalar/object/array type mapping remains the existing finite domain enum.

The existing input-versus-response nullability policy remains authoritative. OpenAPI 3.1 does not broaden where generated Java input contracts accept null.

### 7.3 Unsupported JSON Schema features

The analyzer continues to reject, at the affected endpoint, semantics it cannot faithfully emit or validate. This includes composition, recursion, unsupported additional properties, unsupported exclusive-bound representation, conditionals, arbitrary tuple schemas, and other unsupported keywords that affect the generated contract.

Local component references remain supported under the existing recursion and path guards. External references remain blocked. OpenAPI 3.1 support must not weaken the reference preflight.

## 8. Success Response Media Policy

For every body-bearing success response, the analyzer requires one unambiguous schema-bearing media declaration. The decision order is:

1. accept a single `application/json` declaration;
2. accept a single concrete `application/<subtype>+json` declaration;
3. accept a single schema-bearing `*/*` declaration as `SUPPORTED_WITH_WARNING` with `SUCCESS_MEDIA_TYPE_INFERRED`;
4. reject multiple declarations, a schema-less declaration, or a non-JSON declaration as `UNSUPPORTED`.

All body-bearing success response schemas for an operation must remain supported and structurally identical. Existing bodyless-success compatibility remains unchanged.

Wildcard inference applies only to success responses. Request bodies still require an explicitly supported JSON media type and schema.

The generated runtime does not treat `*/*` as permission to consume arbitrary bytes. A real upstream response body must have `application/json` or a `+json` media type and satisfy existing size and JSON parsing bounds. Otherwise the generated Tool returns the existing safe provider/protocol error.

## 9. Web Architecture

### 9.1 Shared analysis presentation

One Web presenter serializes the canonical analysis result for local and hosted modes. The response includes:

```json
{
  "id": "opaque-specification-id",
  "checksum": "sha256",
  "openApiVersion": "3.1.2",
  "file": {
    "name": "swagger-3.1.yml",
    "byteSize": 12345
  },
  "counts": {
    "total": 26,
    "supported": 12,
    "supportedWithWarning": 8,
    "unsupported": 6
  },
  "operations": [
    {
      "operationId": "getUsers",
      "method": "GET",
      "path": "/api/users",
      "summary": "List users",
      "status": "SUPPORTED_WITH_WARNING",
      "supported": true,
      "issues": [
        {
          "code": "SUCCESS_MEDIA_TYPE_INFERRED",
          "severity": "WARNING",
          "message": "The JSON response media type was inferred from */*"
        }
      ]
    }
  ]
}
```

Counts are derived from the immutable operation list. The browser does not submit or override support status or issue data.

### 9.2 Local mode

Local upload keeps the existing private temporary specification store, size bound, CSRF protection, loopback restrictions, and opaque identifier. Upload analysis returns the shared presentation immediately. Preview and generation re-read the stored immutable source and recompute the canonical plan rather than trusting browser state.

### 9.3 Hosted mode

Hosted upload keeps owner-bound persistence, object storage, authentication, CSRF, quota, and audit boundaries. It accepts the same bounded, sanitized upload basename as local mode and persists that safe label without using it as a storage key. A successful file upload returns the same analysis presentation in its creation response. The asynchronous URL-import flow exposes the presentation through an owner-authorized specification analysis read endpoint after the import reaches `READY`; its display label is the existing safe catalog label and never the raw import URL. A hosted generation request sends only the owner-bound specification ID and strict generation configuration. The worker re-analyzes the persisted source before generation.

Hosted mode exposes an owner-authorized planning preview at the same semantic boundary as local preview. It reads the pinned object through the existing storage boundary, analyzes and plans it under the same byte and reference limits, returns only the existing safe `GenerationPreview`, and deletes its private temporary copy. It does not compile, boot, call an upstream, fetch a URL, or publish an artifact. Generation and validation continue to run through the hosted queue and sandbox rather than in the Web request.

The hosted dashboard retains specification and job history. Its primary create action enters the same guided editor instead of requiring users to author raw configuration JSON. URL imports remain asynchronous; after import analysis is available, the imported specification can enter step 2 through the same owner-authorized editor route.

## 10. Guided Editor Interaction

### 10.1 Step 1: upload

The upload surface is a real file input with an accessible label and a drag-and-drop target. It supports `.yaml`, `.yml`, and `.json` within the existing 10 MiB server bound.

The UI exposes these states:

- idle;
- drag-over;
- uploading/analyzing;
- complete;
- error.

Before the server responds, the browser shows the local file name and size. OpenAPI version, endpoint counts, and support decisions are shown only from the server response. A completed state shows file name, size, exact OpenAPI version, endpoint count, and actions to replace or remove the file. Step 2 remains disabled until analysis completes.

Replacing or removing a file clears the previous specification ID, operation selection, Tool overrides, preview, and generation state. Dragging a new valid file onto the completed surface follows the same replacement behavior.

Keyboard activation, focus visibility, `aria-live` status, and the native file input fallback are required. Drag-and-drop is an enhancement, not the only upload mechanism.

### 10.2 Step 2: endpoint selection

Every analyzed endpoint remains visible and searchable by method, path, operation ID, summary, and description.

- supported endpoints can be selected;
- warning-supported endpoints can be selected and retain a visible warning marker and reason;
- unsupported endpoints are disabled and show every fixed reason inline;
- select-all selects only supported and warning-supported endpoints;
- the selected count and generated Tool count update immediately.

At least one selectable endpoint is required to continue. Warning-supported endpoints do not require a blocking confirmation dialog, but their warnings remain visible in the endpoint row and final generation summary.

### 10.3 Step 3: settings and generation

The default view exposes only project name/group/package fields required by the strict configuration contract and the canonical compatibility profile. Existing deterministic defaults prefill fields where safe.

Selected endpoints appear as collapsed Tool rows. A row summary shows method, path, operation ID, generated Tool name, and whether defaults or overrides apply. Expanding a row exposes Tool name, description, parameter source, and response policy settings. Retry, pagination, response normalization, and other expert controls remain behind a Tool-level advanced disclosure.

Project-level timeout and packaging controls remain behind a project advanced disclosure when the current configuration contract supports them. The UI must not introduce a field the server cannot represent.

A sticky or adjacent summary shows uploaded OpenAPI version, selected Tool count, excluded endpoint count, target profile, warning count, and validation behavior. Generation remains disabled until required fields and a representative validation call are valid. Preview validation is part of this step rather than a separate navigation step.

Generation progress and terminal result remain in the same flow. A generation failure retains the uploaded specification, endpoint selection, and editable settings so the user can correct and retry. Downloads appear only when the existing local or hosted artifact policy permits them.

### 10.4 Visual contract

The approved visual hierarchy follows the existing Thymeleaf application and its current typography, spacing, borders, and controls. This work changes information architecture rather than introducing a new brand system.

Editor labels and guidance follow the approved Korean mock. Machine-readable API fields, error codes, logs, generated source, and repository documentation remain English. User-facing server error messages are translated by fixed UI message keys rather than displaying raw backend text.

The success semantic color changes from the previous bright green to the approved muted teal `--success: #148f77` used in the mock. Other semantic and accent colors remain unchanged. The token is used for borders and indicators; small success text uses the existing primary text color so the approved decorative color does not create a WCAG contrast failure.

The layout remains usable at the existing 400 px acceptance viewport. The desktop summary column collapses below the form on narrow screens.

## 11. Client State and Trust Boundary

Client state contains only opaque specification/job identifiers, canonical profile data, server-returned analysis, user selection, user overrides, preview, and progress. It does not contain provider secret values or private storage paths.

The server is authoritative for:

- OpenAPI version and checksum;
- operation support status and issue codes;
- canonical profile existence;
- Tool policy and schema validation;
- generation and validation stages;
- artifact availability and ownership.

Every preview and generation request recomputes the canonical plan from the pinned specification. A client cannot enable an unsupported operation by altering JavaScript state or request JSON.

## 12. Error Handling

Upload-level failures keep the user in step 1 and preserve only the local file name needed to retry. Analysis-level unsupported operations enter step 2 rather than becoming an upload failure.

Client-visible errors use fixed, non-leaking messages. The UI places a concise error near the failed action and links it to the page-level accessible error summary. It does not display raw exception text, parser output, request bodies, filesystem paths, process output, secret candidates, or stack traces.

Network interruption or a generation failure does not silently reset the wizard. Retrying an upload or generation is an explicit action. Hosted requests preserve existing idempotency keys and owner checks.

## 13. Verification Strategy

### 13.1 Analyzer and domain

- Accept exact OpenAPI 3.0.x and 3.1.x versions and reject all other version forms.
- Normalize OpenAPI 3.0 nullable and OpenAPI 3.1 single-null unions to equivalent canonical schemas.
- Reject true multi-type unions and unsupported 3.1 keywords with typed operation issues.
- Preserve local reference and recursion guards for both versions.
- Verify fixed issue ordering, codes, severities, and non-leaking messages.
- Verify exact media policy for `application/json`, specific `+json`, inferred `*/*`, multiple media declarations, non-JSON media, and missing schemas.

### 13.2 Paired root acceptance documents

- Analyze repository-root `swagger-3.0.yml` and `swagger-3.1.yml` as primary fixtures.
- Assert the exact 26-operation inventory from each source.
- Compare operations by method, path, and operation ID.
- Assert equivalent canonical schemas, status, and issue codes after version-specific normalization.
- Assert inferred wildcard media warnings where the paired documents declare schema-bearing `*/*` success bodies.
- Exercise both documents through CLI inspection and Web upload analysis.

The fixture files must be versioned inputs before CI depends on them. Tests must not rewrite them.

### 13.3 Web unit and integration

- Upload via native file selection and drag-and-drop.
- Verify idle, drag-over, analyzing, complete, error, replace, and remove states.
- Verify that server version and counts are not derived from browser file parsing.
- Render all support states and inline reasons.
- Prevent unsupported operation selection and select only selectable operations in select-all.
- Verify search, selected counts, step gating, focus order, live regions, and the 400 px layout.
- Verify progressive disclosures and preservation of user state after preview or generation failure.
- Verify local CSRF and loopback contracts and hosted authentication, ownership, idempotency, and artifact access remain unchanged.

### 13.4 End-to-end generation

- Generate at least one representative Tool from each paired root document.
- Assert the two generated Tool contracts are equivalent for equivalent operations.
- Run the existing compile, application context, MCP initialize, tools/list, tools/call, and exact upstream request verification appropriate to the selected profile.
- Return a safe protocol/provider failure when a wildcard-declared upstream response returns a non-JSON actual media type.

## 14. Complexity and Performance

Analysis remains linear in the loaded document and normalized schema graph, `O(N)`, subject to the existing recursion and size bounds. Operation presentation and client filtering are linear in the number of endpoints, `O(E)`. Stored analysis and client state are `O(E + S)`, where `S` is the bounded canonical schema graph.

No additional parser pass runs in the browser. The server deliberately re-analyzes a pinned source for preview and generation to preserve the trust boundary.

## 15. Delivery Boundaries

The implementation is split into independently reviewable slices:

1. canonical support decision and OpenAPI 3.1 normalization;
2. wildcard success media policy and runtime regression coverage;
3. shared Web analysis presentation for local and hosted modes;
4. guided upload and endpoint selection UI;
5. progressive settings, preview, generation, and responsive accessibility;
6. paired root fixture and full acceptance coverage.

The design does not require unrelated repository restructuring or emitter changes. Any parser limitation that prevents fail-closed detection of a 3.1 semantic must be resolved in the OpenAPI adapter boundary rather than approximated in the browser or emitter.

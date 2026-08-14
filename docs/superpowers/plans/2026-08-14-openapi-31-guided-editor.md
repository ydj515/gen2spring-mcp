# OpenAPI 3.1 and Guided Endpoint Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support the approved bounded OpenAPI 3.0.x/3.1.x contract and replace the local and hosted Web creation flow with the approved three-step guided endpoint editor.

**Architecture:** The domain owns one typed operation support decision, the Swagger adapter normalizes version-specific syntax into that decision, and the application exposes one immutable analysis view to CLI and Web adapters. Local and hosted Web modes reuse the same analysis and editor model while preserving their existing storage, ownership, queue, sandbox, and artifact boundaries. The browser renders server-owned support decisions and never parses OpenAPI semantics.

**Tech Stack:** Java 21, Gradle 9.6.1, Swagger Parser, Spring Boot MVC, Thymeleaf, ECMAScript modules, JUnit 5, Mockito, PostgreSQL adapter tests, existing Spring AI 1/2 generated-runtime suites.

## Global Constraints

- Implement the approved design in `docs/superpowers/specs/2026-08-14-openapi-31-guided-editor-design.md`; do not broaden it into full JSON Schema 2020-12 support.
- Preserve all existing source-size, local-reference, secret, CSRF, authentication, ownership, idempotency, queue, sandbox, process-cleanup, and artifact-access contracts.
- Keep `supported` as a derived compatibility value. `OperationSupport.Status` and typed issues are the only source of truth.
- Emit only fixed issue codes, severities, and safe messages. Never serialize parser messages, source fragments, paths, URLs, header values, secrets, process output, or stack traces.
- Keep schema/document traversal `O(N)` and operation presentation/filtering `O(E)` under existing bounds.
- Treat repository-root `swagger-3.0.yml` and `swagger-3.1.yml` as immutable supplied acceptance inputs. Tests read them and never rewrite them.
- Keep the current Thymeleaf/ES-module stack. Do not add a frontend framework or browser-side OpenAPI parser.
- Keep UI copy Korean and machine-readable API names, codes, logs, generated source, tests, and repository documentation English.
- Change only the success semantic color to `#148f77`; keep the remaining established visual tokens unchanged.
- Complete each task with focused RED/GREEN evidence and one scoped Conventional Commit. Do not push from an implementation task unless the user explicitly requests it.

---

## Task 1: Introduce the canonical operation support contract

**Files:**

- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/specification/OperationSupport.java`
- Create: `modules/domain/src/test/java/io/gen2spring/mcp/domain/specification/OperationSupportTest.java`
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/specification/OpenApiDocument.java`
- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerSchemaNormalizer.java`
- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzer.java`
- Modify: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzerTest.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/planning/GenerationPlannerTest.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/toolmodel/ToolModelFactoryTest.java`

**Interfaces:**

- Produce `OperationSupport(Status status, List<Issue> issues)` with `Status`, `Severity`, and the exact finite `IssueCode` set approved by the design.
- Each `IssueCode` owns its fixed severity and safe English message; `Issue` carries no arbitrary message input.
- Replace `ApiOperation`'s independent `boolean supported` and `List<String> warnings` components with `OperationSupport support`.
- Preserve derived `ApiOperation.supported()` and `ApiOperation.warnings()` accessors for transport and existing consumers.
- Extend `OpenApiDocument.HttpMethod` with `HEAD`, `OPTIONS`, and `TRACE` while retaining the emitter-supported five-method boundary.
- Keep `ApiSchema` emitter-facing compatibility fields unchanged; the Swagger adapter converts schema findings to typed operation issues before returning the document.

**Steps:**

- [ ] Add domain tests for all three statuses, deterministic issue ordering/deduplication, derived compatibility accessors, finite messages, and rejection of null/invalid construction.
- [ ] Run `mise exec -- ./gradlew :modules:domain:test --tests 'io.gen2spring.mcp.domain.specification.OperationSupportTest' --no-daemon --non-interactive` and record the expected compile/test RED.
- [ ] Implement the immutable domain contract and migrate `ApiOperation` construction sites without a second mutable truth source.
- [ ] Add analyzer tests showing missing IDs, duplicate IDs, HEAD/OPTIONS/TRACE, recursion, unsupported parameters, schema, request body, success response, and security requirements become exact operation issues instead of document failures or free-form warnings.
- [ ] Ensure every operation sharing a duplicate ID receives `OPERATION_ID_DUPLICATED`, unrelated operations remain selectable, and operation ordering remains path/method deterministic.
- [ ] Run `mise exec -- ./gradlew :modules:domain:test :modules:application:test :modules:adapters:openapi:test --no-daemon --non-interactive --rerun-tasks` and require zero failures.
- [ ] Review the diff for raw-message construction and commit as `feat(domain): model operation support decisions`.

---

## Task 2: Normalize the approved OpenAPI 3.1 schema boundary

**Files:**

- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzer.java`
- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerSchemaNormalizer.java`
- Modify: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzerTest.java`
- Create: `modules/adapters/openapi/src/test/resources/openapi/openapi-31-supported.yaml`
- Create: `modules/adapters/openapi/src/test/resources/openapi/openapi-31-unsupported.yaml`

**Interfaces:**

- Accept only `3.0.<digits>` and `3.1.<digits>` with no suffix.
- For 3.1, accept an absent dialect or exact `https://spec.openapis.org/oas/3.1/dialect/base`; reject any other dialect with the existing fixed document-level version failure.
- Normalize `type: [T, "null"]` to the existing canonical type plus `nullable=true` only when exactly one supported non-null type exists.
- Return `SCHEMA_MULTI_TYPE_UNSUPPORTED`, `SCHEMA_NULLABILITY_UNSUPPORTED`, or the appropriate existing typed issue for unsupported 3.1 semantics at operation scope.
- Preserve external-reference and recursion preflight behavior for both versions.

**Steps:**

- [ ] Add parameterized RED cases for exact accepted/rejected version strings and supported/unsupported dialect values.
- [ ] Add paired 3.0 `nullable: true` and 3.1 single-null-union expectations, plus zero/multiple non-null type, composition, conditionals, tuple, unsupported additional-properties, and exclusive-bound cases.
- [ ] Run `mise exec -- ./gradlew :modules:adapters:openapi:test --tests 'io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzerTest' --no-daemon --non-interactive` and confirm the 3.1 cases fail for the intended version/schema reasons.
- [ ] Implement version/dialect validation and one canonical type-set normalization path; do not infer unsupported semantics from browser state or parser text.
- [ ] Re-run the focused analyzer test, then `mise exec -- ./gradlew :modules:adapters:openapi:test --no-daemon --non-interactive --rerun-tasks`.
- [ ] Commit as `feat(openapi): normalize OpenAPI 3.1 schemas`.

---

## Task 3: Implement success-response media selection and pin runtime fail-closed behavior

**Files:**

- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzer.java`
- Modify: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzerTest.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedRuntimeRegressionTest.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/GeneratedProjectSmokeTest.java`

**Interfaces:**

- Select exactly one schema-bearing `application/json`, one concrete `application/<subtype>+json`, or one schema-bearing `*/*` success declaration.
- Mark `*/*` as selectable `SUPPORTED_WITH_WARNING` with `SUCCESS_MEDIA_TYPE_INFERRED`.
- Mark multiple, schema-less, or non-JSON declarations `UNSUPPORTED` with `SUCCESS_MEDIA_TYPE_UNSUPPORTED` or `SUCCESS_SCHEMA_UNSUPPORTED`.
- Require every body-bearing success schema to be supported and structurally identical; preserve bodyless success behavior.
- Keep request bodies explicit JSON-only; wildcard inference never applies to requests.
- Preserve generated runtime behavior: actual JSON and `+json` responses are accepted, while missing/non-JSON actual media produces the fixed protocol/provider error.

**Steps:**

- [ ] Add analyzer RED cases covering the complete ordered media decision table and structurally different success schemas.
- [ ] Run the focused analyzer test and confirm wildcard and `+json` cases fail before implementation.
- [ ] Replace the current exact `application/json` lookup with one deterministic success-media selector shared by response validation and schema normalization.
- [ ] Add one focused assertion to each existing generated-runtime suite tying wildcard-declared metadata to actual `application/problem+json` success and `text/plain` protocol failure; reuse existing generated project fixtures rather than changing runtime code unless the RED exposes a real gap.
- [ ] Run `mise exec -- ./gradlew :modules:adapters:openapi:test --no-daemon --non-interactive --rerun-tasks`.
- [ ] Run `mise exec -- ./gradlew :modules:adapters:emitters:spring-ai-1:test --tests 'io.gen2spring.mcp.adapter.emitter.springai1.GeneratedRuntimeRegressionTest' --no-daemon --non-interactive --rerun-tasks`.
- [ ] Run `mise exec -- ./gradlew :modules:adapters:emitters:spring-ai-2:test --tests 'io.gen2spring.mcp.adapter.emitter.springai2.GeneratedProjectSmokeTest' --no-daemon --non-interactive --rerun-tasks`.
- [ ] Commit as `feat(openapi): infer bounded JSON success media`.

---

## Task 4: Share one analysis presentation across CLI and Web

**Files:**

- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/analysis/SpecificationAnalysisView.java`
- Create: `modules/application/src/test/java/io/gen2spring/mcp/application/analysis/SpecificationAnalysisViewTest.java`
- Modify: `apps/cli/src/main/java/io/gen2spring/mcp/app/cli/command/CliApplication.java`
- Modify: `apps/cli/src/test/java/io/gen2spring/mcp/app/cli/command/CliApplicationTest.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/SpecificationAnalysisPresenter.java`
- Create: `apps/web/src/test/java/io/gen2spring/mcp/app/web/api/SpecificationAnalysisPresenterTest.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/PreviewHandler.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`

**Interfaces:**

- Produce `SpecificationAnalysisView.from(OpenApiDocument)` with document metadata, security schemes, document warnings, deterministic counts, and operations containing status, derived supported, and typed issues.
- Produce one Web presenter that adds opaque specification ID and bounded file metadata without changing the canonical analysis decision.
- Keep CLI inspection JSON compatible for existing fields while adding status/issues/counts from the shared view.
- Local upload returns the approved `id`, `checksum`, `openApiVersion`, `file`, `counts`, `operations`, `securitySchemes`, and document `warnings` fields immediately.

**Steps:**

- [ ] Write an independent application-layer expected view containing all three statuses and verify count derivation, immutable order, compatibility fields, and exact safe issue serialization.
- [ ] Run `mise exec -- ./gradlew :modules:application:test --tests 'io.gen2spring.mcp.application.analysis.SpecificationAnalysisViewTest' --no-daemon --non-interactive` and capture the missing-view RED.
- [ ] Implement the view as a pure mapping with no Jackson or Web dependency.
- [ ] Add CLI and local Web contract RED cases proving both adapters serialize the same operation/status/issue values and that the Web response includes only the sanitized file name and byte size.
- [ ] Implement the CLI and local Web mappings through the shared view; delete duplicate operation serialization from `PreviewHandler`.
- [ ] Run `mise exec -- ./gradlew :modules:application:test :apps:cli:test :apps:web:test --no-daemon --non-interactive --rerun-tasks`.
- [ ] Commit as `feat(application): expose canonical analysis views`.

---

## Task 5: Add owner-authorized hosted analysis and planning preview

**Files:**

- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/specification/SpecificationCatalog.java`
- Modify: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresSpecificationCatalog.java`
- Create: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresSpecificationCatalogTest.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedSubmissionService.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedSpecificationController.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedSubmissionServiceTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedControllerContractTest.java`

**Interfaces:**

- Extend `SpecificationCatalog.Registration` with a sanitized non-empty `displayLabel` bounded to the existing `varchar(160)` column; do not create a migration.
- Change hosted upload to accept the bounded basename and return an immutable result containing the new ID and shared analysis view.
- Add owner-authorized `GET /api/specifications/{id}/analysis` for READY uploaded or imported specifications.
- Add owner-authorized `POST /api/specifications/{id}/preview` that reads the pinned object, verifies size/checksum metadata, writes one private temporary file, calls the existing preview pipeline, and deletes the temporary file.
- Preview must not compile, boot, call an upstream, import a URL, enqueue a job, or publish an artifact.

**Steps:**

- [ ] Add persistence RED coverage proving uploaded labels round-trip through the existing catalog/resource-store schema and invalid labels fail with fixed non-leaking errors.
- [ ] Add service/controller RED cases for upload response parity, URL-import READY analysis read, cross-owner/not-found rejection, pinned-object checksum mismatch, preview success, and temporary-file cleanup on success/failure/fatal error.
- [ ] Run the focused Postgres and hosted Web tests and confirm missing contracts fail before production changes.
- [ ] Implement label persistence and the hosted analysis/preview methods through `ObjectStorage.get`, `HostedResourceStore.specification`, the shared analysis presenter, and the existing application pipeline.
- [ ] Keep upload/import/generation ownership, CSRF, quota, idempotency, queue, and sandbox flows unchanged.
- [ ] Run `mise exec -- ./gradlew :modules:adapters:persistence-postgres:test :apps:web:test --no-daemon --non-interactive --rerun-tasks`.
- [ ] Commit as `feat(web): expose hosted analysis and preview`.

---

## Task 6: Build the guided upload and endpoint-selection experience

**Files:**

- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/page/EditorController.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/page/DashboardController.java`
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/templates/dashboard.html`
- Modify: `apps/web/src/main/resources/static/api.js`
- Modify: `apps/web/src/main/resources/static/app.js`
- Modify: `apps/web/src/main/resources/static/state.js`
- Create: `apps/web/src/main/resources/static/upload.js`
- Create: `apps/web/src/main/resources/static/operations.js`
- Modify: `apps/web/src/main/resources/static/styles.css`
- Modify: `apps/web/src/main/resources/static/hosted.js`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedModeContractTest.java`

**Interfaces:**

- Serve `/editor` in local and hosted modes; keep local `/` on the editor and hosted `/` on history/dashboard with a primary create link to `/editor`.
- Implement upload states `idle`, `drag-over`, `analyzing`, `completed`, and `error` around a real file input.
- Show local file name/size immediately, but show version, endpoint counts, status, and issues only from the server response.
- Render every endpoint. Unsupported rows are disabled with all reasons; warning-supported rows are selectable with warnings; select-all affects only selectable rows.
- Replacing/removing a file clears specification ID, selections, overrides, preview, and job state.
- Use `--success: #148f77` for semantic borders/indicators; keep small text on the existing primary text color.

**Steps:**

- [ ] Replace the five-step static contract with RED assertions for the approved three-step Korean hierarchy, native file input, drop target, live regions, completed-file actions, support counts, search, selectable-only select-all, and fixed issue rendering.
- [ ] Add source-level state tests asserting drag event handling, keyboard activation, file validation, reset semantics, server-authoritative analysis, and the absence of browser OpenAPI parsing/`innerHTML`/secret fields.
- [ ] Run `mise exec -- ./gradlew :apps:web:test --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' --tests 'io.gen2spring.mcp.app.web.HostedModeContractTest' --no-daemon --non-interactive` and capture the five-step RED.
- [ ] Implement `upload.js` as the upload-state owner and `operations.js` as the endpoint filter/selection owner; keep configuration serialization in `editor.js` and top-level orchestration in `app.js`.
- [ ] Update Thymeleaf and CSS to match the approved reference hierarchy without introducing a new brand system or nonfunctional controls.
- [ ] Run `mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks`.
- [ ] Start `mise run ui` and perform manual in-app browser QA for file selection, actual drag/drop, completed replacement/removal, warning/unsupported reasons, select-all, keyboard focus, and hosted `/editor` entry. Record that this is manual UI evidence, not a unit-test result.
- [ ] Commit as `feat(web): guide OpenAPI endpoint selection`.

---

## Task 7: Integrate settings, preview, generation, and responsive accessibility

**Files:**

- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/app.js`
- Modify: `apps/web/src/main/resources/static/editor.js`
- Modify: `apps/web/src/main/resources/static/state.js`
- Modify: `apps/web/src/main/resources/static/styles.css`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`
- Modify: `apps/web/src/integrationTest/java/io/gen2spring/mcp/app/web/LocalOperationEditorIntegrationTest.java`

**Interfaces:**

- Step 3 shows required project/profile fields, collapsed selected Tool rows, Tool-level advanced disclosures, representative validation input, preview, generation, progress, and terminal downloads in one flow.
- Configuration requests contain only selected operation IDs and user-editable overrides; status/issues never round-trip as authority.
- Preview or generation failure preserves uploaded analysis, selection, and editable settings.
- Hosted generation uses the existing idempotent job endpoint; local generation uses the existing local job endpoint.
- The summary shows version, selected/excluded/warning counts, target profile, and validation behavior.
- Desktop summary collapses below the form at the existing 400 px acceptance viewport with visible focus and 44 px interactive targets.

**Steps:**

- [ ] Add RED contracts for collapsed Tool rows, default versus override summaries, advanced disclosure, representative-call gating, sticky summary, failure-state preservation, local/hosted endpoint selection, and 400 px source-level responsive rules.
- [ ] Run focused Web tests and verify the missing progressive-disclosure/gating assertions fail.
- [ ] Recompose the existing editor controls into step 3 without dropping retry, pagination, response normalization, output, secret source, preview, progress, cancellation, and artifact behavior.
- [ ] Ensure preview invalidation occurs only when authoritative configuration changes, not when a row is merely expanded or filtered.
- [ ] Extend local integration coverage to upload, choose only selectable operations, preview, start generation, retain state on a safe failure, and preserve existing CSRF/loopback behavior.
- [ ] Run `mise exec -- ./gradlew :apps:web:test :apps:web:integrationTest --no-daemon --non-interactive --rerun-tasks`.
- [ ] Repeat in-app browser QA at desktop and 400 px, comparing the implementation to the approved reference images and checking interaction rather than screenshots alone.
- [ ] Commit as `feat(web): integrate guided generation settings`.

---

## Task 8: Version paired fixtures and complete cross-adapter acceptance

**Files:**

- Add unchanged supplied fixture: `swagger-3.0.yml`
- Add unchanged supplied fixture: `swagger-3.1.yml`
- Modify: `modules/adapters/openapi/build.gradle.kts`
- Create: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/OpenApiVersionPairAcceptanceTest.java`
- Modify: `apps/cli/src/test/java/io/gen2spring/mcp/app/cli/InstalledCliTest.java`
- Modify: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`
- Modify: `README.md`
- Modify: `docs/user-guide.md`

**Interfaces:**

- Pass the repository root to the OpenAPI test task through a read-only test system property; do not copy or rewrite the paired fixtures.
- Assert each fixture declares exactly 26 operation IDs and compare operations by method, path, and operation ID.
- Require equivalent canonical schema, status, and issue-code decisions after version normalization, including wildcard warning parity.
- Exercise both fixtures through CLI inspection and local/hosted Web analysis.
- Generate one equivalent representative Tool from each fixture and compare the Tool schema and exact upstream call/result contract.
- Update only current OpenAPI support/user-flow statements; retain historical approved specifications as historical records.

**Steps:**

- [ ] Add the paired acceptance test and deliberately assert one wrong operation count/status to capture an oracle RED before restoring the approved literal expectations.
- [ ] Run `mise exec -- ./gradlew :modules:adapters:openapi:test --tests 'io.gen2spring.mcp.adapter.openapi.swagger.OpenApiVersionPairAcceptanceTest' --no-daemon --non-interactive` and record the RED, then restore the exact independent expectations and require GREEN.
- [ ] Extend installed CLI and Web tests to read both root fixtures and verify exact version, counts, statuses, issue codes, and safe output without duplicating analyzer logic in the oracle.
- [ ] Extend the existing generation integration journey with one representative equivalent operation from each version and the existing compile/context/MCP/exact-one-upstream gates.
- [ ] Update `README.md` and `docs/user-guide.md` from OpenAPI 3.0-only/five-step wording to the bounded 3.0.x/3.1.x and guided editor contract.
- [ ] Run the affected full acceptance command:
  `GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" mise exec -- ./gradlew :modules:domain:test :modules:application:test :modules:adapters:openapi:test :modules:adapters:emitters:spring-ai-1:test :modules:adapters:emitters:spring-ai-2:test :modules:adapters:persistence-postgres:test :apps:cli:test :apps:cli:integrationTest :apps:web:test :apps:web:integrationTest --no-daemon --non-interactive --rerun-tasks`.
- [ ] Run `git diff --check`, scan generated/test reports for raw source paths, parser details, URLs, secrets, and stack traces, and confirm no test rewrote either root fixture.
- [ ] Perform final desktop/400 px in-app browser QA for both 3.0 and 3.1 uploads in local mode and the owner-authorized hosted flow.
- [ ] Commit as `feat: support OpenAPI 3.1 guided generation`.

---

## Final Review Gate

- [ ] Trace every approved design requirement to at least one task and test above.
- [ ] Confirm every task is executable without unresolved decisions, invented dependencies, or browser-side support rules.
- [ ] Confirm `OperationSupport`, shared analysis JSON, editor state, and generation configuration use consistent status/issue/selection types.
- [ ] Confirm unsupported endpoints are visible but cannot enter preview/generation after client tampering because the server re-analyzes the pinned source.
- [ ] Confirm local and hosted modes differ only at their approved persistence/job boundaries and share the same analysis and editor semantics.
- [ ] Request a scoped code review before push/PR work, then apply only accepted findings with focused regression evidence.

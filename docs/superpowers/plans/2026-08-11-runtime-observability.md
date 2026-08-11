# Generated Runtime Metrics and OpenTelemetry Implementation Plan

> **Agent workflow:** Execute with `superpowers:subagent-driven-development`, one task at a time. Every
> accepted review change starts with a regression test. Do not push.

**Goal:** Generate the same safe, bounded runtime metrics and OpenTelemetry traces for Spring AI 1.1 and
2.0 projects on Java 17 and Java 21.

**Architecture:** Both version-specific emitters render an isolated `RuntimeTelemetry` component using
Micrometer Observation. Boot-specific project renderers own dependencies and export properties. Tool and
provider scopes use finite low-cardinality tags, propagate context into the bounded worker executor, and
reuse active trace IDs in provider errors without exporting raw failures or secrets.

## Global constraints

- Preserve the four existing profile IDs and `CompatibilityProfile.p0()` alias.
- Bump Spring AI 1 template to v2, Spring AI 2 template to v3, and runtime to `0.3.0`.
- Keep Boot/AI/Gradle/Java/image pins unchanged.
- Exporters and Prometheus endpoint are opt-in; no default outbound endpoint.
- Never record argument, URL, body, provider message/code, credential, exception message, or stack.
- Keep fatal `Error` identity, timeout, queue, masking, response normalization, explicit schema, and exactly-one validation contracts.
- Do not implement Generator API control-plane metrics in this slice.
- Use `feat:`, `refactor:`, or `docs:` commits; no `fix:` commits.

### Task 1: Version the observability capability

**Files:**

- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/observability/RuntimeObservabilityContract.java`
- Create: `generator-domain/src/test/java/io/gen2spring/mcp/domain/observability/RuntimeObservabilityContractTest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistry.java`
- Modify: `generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistryTest.java`
- Modify: `generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileTest.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolModelFactory.java`
- Modify: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`

- [ ] RED: expect `spring-ai-1-v2`, `spring-ai-2-v3`, runtime `0.3.0` for all four canonical profiles.
- [ ] GREEN: update canonical defaults without changing IDs, target versions, images, order, or identity.
- [ ] RED/GREEN: reserve `traceparent`, `tracestate`, `baggage`, `b3`, and `x-b3-*` header parameters/API-key
      targets case-insensitively and fail before source generation.
- [ ] Run domain/core/CLI focused and full unit tests.
- [ ] Commit: `feat(domain): version runtime observability`

### Task 2: Render Boot-specific observability dependencies and safe defaults

**Files:**

- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ProjectFileRenderer.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ProjectFileRendererTest.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`

- [ ] RED: assert exact family dependency sets and version-managed coordinates.
- [ ] RED: assert health-only exposure, exporter-off properties, and sampling/W3C env without YAML common tags.
- [ ] GREEN: render Boot 3 and Boot 4 dependency/property adapters from the approved design.
- [ ] Assert no dynamic version, default endpoint, public Prometheus exposure, or duplicate bridge/exporter.
- [ ] Run both renderer modules.
- [ ] Commit: `feat(spring-ai): configure runtime observability`

### Task 3: Generate the canonical RuntimeTelemetry component

**Files:**

- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/RuntimeTelemetryRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/JavaSourceRenderer.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/JavaSourceRendererTest.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeTelemetryRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`

- [ ] RED: exact `gen2spring.runtime.*` metric/span names, fixed profile/category/status metric tags,
      trace-only Tool/operation attributes, gauges, summary, active trace/fallback contract.
- [ ] RED: operation IDs outside the exact 128-character ASCII source-name contract fail with a fixed
      non-leaking startup error; valid boundary values remain exact trace-only attributes.
- [ ] GREEN: implement package-isolated but semantically identical AI1/AI2 renderers.
- [ ] Assert no raw Throwable recording, no Tool/operation meter tag, and forbidden high-cardinality source tokens.
- [ ] Run AI1/AI2 source renderer suites.
- [ ] Commit: `feat(runtime): generate canonical telemetry`

### Task 4: Instrument Tool and provider execution

**Files:**

- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/RuntimeTelemetryRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ToolCallbackConfigurationRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ResponseRuntimeRenderer.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedRuntimeRegressionTest.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeTelemetryRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolCallbackConfigurationRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ResponseRuntimeRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`

- [ ] RED: Tool/provider timers, outcome/category/status tags, response bytes and executor gauges.
- [ ] RED: worker context propagation, disabled automatic HTTP observation, single-header W3C propagation,
      active trace ID reuse and no-span 32-hex fallback.
- [ ] RED: binding-created propagation headers are removed after binding; exactly one generated `traceparent`
      reaches the mock upstream and no tracestate/baggage/B3 header remains.
- [ ] RED: generated `traceparent` is exact version `00` with non-zero lowercase trace/span IDs and `00|01`
      flags; invalid current context sends no propagation header and user-provided propagation values never win.
- [ ] RED: provider/internal/fatal/timeout/rejection paths do not export raw failure or secret values.
- [ ] RED: caller-owned single-stop lifecycle classifies submit rejection, queued/running timeout and normal/error
      completion exactly once; cancel-late-success cannot mutate outcome/status/bytes or stop the span twice.
- [ ] GREEN: inject `RuntimeTelemetry`, wrap executor, open/close scopes, preserve all existing error semantics.
- [ ] Run generated focused and full AI1/AI2 tests on Java 17 and Java 21.
- [ ] Commit: `feat(runtime): instrument generated Tool execution`

### Task 5: Prove real Boot observability on four targets

**Files:**

- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedProjectSmokeTest.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedRuntimeRegressionTest.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedSecretSafetyTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedSecretSafetyTest.java`

- [ ] Assert ObservationRegistry, MeterRegistry, Tracer beans for Boot 3/4 on Java 17/21.
- [ ] Assert exporter beans/network absent by default and only health endpoint exposed.
- [ ] Opt in Prometheus in test and verify exact generated metric names after live MCP Tool calls.
- [ ] Use test-only in-memory OTel exporter and always-on sampler to verify exact spans, status, attributes,
      parent/child relationship, provider envelope trace ID and mock-upstream `traceparent`.
- [ ] Verify telemetry readback contains no secrets, URLs, raw bodies, arguments, exception messages, or stacks.
- [ ] Run full emitter suites.
- [ ] Commit: `feat(validation): verify generated observability`

### Task 6: Extend installed CLI four-profile acceptance

**Files:**

- Modify: `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`

- [ ] Update exact template/runtime metadata for all four profiles.
- [ ] Execute each profile twice and retain deterministic archive/report normalization contract.
- [ ] Boot first artifact with selected target JDK; run raw MCP call and independent metric/trace oracle.
- [ ] Preserve exactly one upstream request, late duplicate absence, and no leak markers.
- [ ] Run combined emitters + CLI unit/integration suites.
- [ ] Commit: `feat(cli): validate observable runtime profiles`

### Task 7: Documentation, full acceptance, and review

**Files:**

- Modify: `README.md`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ProjectFileRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`
- Modify: `docs/superpowers/specs/2026-08-11-runtime-observability-design.md` only for approved corrections
- Modify: `docs/superpowers/plans/2026-08-11-runtime-observability.md` only for approved corrections

- [ ] Document metric/span names, finite tags, safe opt-in env properties, scrape/export examples, trace ID semantics.
- [ ] Remove metrics/OpenTelemetry from unfinished P1; keep Windows and Generator API/UI unfinished.
- [ ] Run affected modules.
- [ ] Run exact acceptance:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] Perform diff, secret/path/stack, process, metric/span cardinality readback.
- [ ] Run scoped reviews per task and one independent whole-slice review. Critical/Important findings block completion.
- [ ] Commit: `docs: document runtime observability`

## Completion gate

- [ ] AI1 profiles expose template v2, AI2 profiles expose template v3, and all expose runtime `0.3.0`
      with unchanged target pins.
- [ ] AI1/AI2 generated builds contain the approved dependency adapter and safe export-off defaults.
- [ ] Tool/provider observations, meters, context propagation, trace ID reuse, and fallback are exact.
- [ ] No high-cardinality, secret, raw payload, URL, exception message, or stack enters telemetry.
- [ ] Java 17/21 generated projects compile, test, boot, expose opt-in metrics, and pass live MCP journeys.
- [ ] Installed CLI remains deterministic and full repository acceptance has zero failures/errors.
- [ ] Independent final review reports zero Critical/Important findings.

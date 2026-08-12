# Repository Structure Refactor Design

## 1. Purpose

The repository already represents a generator product, so the repeated `generator-*` module prefix no longer adds
meaning. More importantly, the current flat module and package layout obscures responsibility boundaries:

- ten production modules live directly under the repository root;
- most modules use one flat Java package even when they contain unrelated lifecycle, protocol, filesystem, and UI
  responsibilities;
- `generator-core` mixes orchestration, validation fixtures, filesystem writes, checksums, manifests, reports, and
  archives;
- `generator-application` is primarily a composition root even though its name implies use-case ownership;
- Spring AI 1 and Spring AI 2 contain parallel renderer trees with substantial behavioral duplication;
- large classes are difficult to navigate, but line count alone does not prove that they should be split.

This refactor establishes an explicit layered modular-monolith structure, reorganizes all internal Java packages, and
preserves user-visible generator behavior.

## 2. Goals

- Remove the redundant `generator-*` Gradle project prefix.
- Group production modules by architectural role rather than placing every project at the repository root.
- Make the dependency direction visible in both Gradle project paths and Java packages.
- Give application use cases ownership of generation orchestration without concrete adapter dependencies.
- Keep the framework-neutral Tool IR independent of Spring AI and MCP SDK types.
- Preserve isolated Spring AI 1 and Spring AI 2 emitters while extracting only genuinely shared rendering behavior.
- Make future `McpJavaSdkToolEmitter` support additive rather than conditional.
- Split classes only when responsibility, dependency, lifecycle, or failure boundaries justify the split.
- Keep CLI, Web API, generated project, validation, and archive contracts compatible.
- Keep every migration step buildable and independently reviewable.

## 3. Non-goals

- No generated project feature or compatibility profile change.
- No CLI option, command, exit code, executable-name, or stdout contract change.
- No Web route, request/response schema, Thymeleaf behavior, or security-policy change.
- No Spring AI, Spring Boot, Gradle, Java, Jackson, or MCP dependency upgrade.
- No new `McpJavaSdkToolEmitter` implementation or compatibility profile.
- No deprecated Java forwarding packages or Gradle alias projects for the old hierarchy.
- No class splitting solely to satisfy a line-count threshold.
- No broad runtime optimization or algorithm rewrite.

## 4. Selected Architecture

### 4.1 Gradle project hierarchy

```text
gen2spring-mcp
├── modules
│   ├── domain
│   ├── application
│   ├── adapters
│   │   ├── configuration
│   │   ├── openapi
│   │   ├── filesystem
│   │   ├── validation
│   │   └── emitters
│   │       ├── support
│   │       ├── spring-ai-1
│   │       └── spring-ai-2
│   └── bootstrap
└── apps
    ├── cli
    └── web
```

The corresponding Gradle project paths are:

```text
:modules:domain
:modules:application
:modules:adapters:configuration
:modules:adapters:openapi
:modules:adapters:filesystem
:modules:adapters:validation
:modules:adapters:emitters:support
:modules:adapters:emitters:spring-ai-1
:modules:adapters:emitters:spring-ai-2
:modules:bootstrap
:apps:cli
:apps:web
```

Intermediate Gradle projects such as `:modules`, `:modules:adapters`, `:modules:adapters:emitters`, and `:apps` are
organizational aggregators only. They must not receive the Java plugin, publish an artifact, or appear as production
modules. Root build conventions apply only to leaf projects with an explicit build script. The final build must verify
this distinction through the Gradle project model rather than relying on directory naming alone.

### 4.2 Dependency direction

```text
apps
  |
  v
bootstrap
  |
  v
adapters ------> application ------> domain
```

Production dependencies must follow these rules:

- `domain` has no dependency on another repository module.
- `application` depends only on `domain`.
- each adapter depends only on `application`, `domain`, and its required external libraries;
- one version-specific emitter must never depend on another emitter;
- `bootstrap` is the only module that knows every concrete adapter implementation;
- CLI and Web depend on `bootstrap` and the inbound application API, not on concrete generation adapters;
- test-fixture dependencies do not authorize the same production dependency.

Peer-adapter dependencies are prohibited. If two adapters need the same behavior, the behavior is either an application
policy, an application port, or emitter-neutral support. It must not become an unowned `common`, `core`, `util`, or `impl`
package.

## 5. Module Responsibilities

### 5.1 `modules/domain`

Owns immutable generator concepts and invariants:

- normalized specification model;
- Tool definition, input, output, parameter, and HTTP execution model;
- compatibility profile;
- retry, pagination, and response-normalization values;
- runtime observability contract;
- canonical generator error code and safe failure type.

It must not import Jackson, Swagger, Spring, Gradle, MCP SDK, or filesystem adapter types.

### 5.2 `modules/application`

Owns generator use cases and ports:

- generation and preview commands;
- generation and preview use cases;
- planning and profile resolution;
- Tool model construction policies;
- expected Tool call and response planning;
- inbound use-case contracts;
- outbound analysis, project generation, validation, workspace, metadata, checksum, and packaging ports.

It must not import Jackson, Swagger, Spring, Gradle, MCP SDK, or a concrete adapter package.

### 5.3 `modules/adapters/configuration`

Parses bounded YAML or JSON input and maps it to an application command. It owns external configuration DTOs, Jackson
mapping, semantic input diagnostics, and local configuration-path boundaries.

### 5.4 `modules/adapters/openapi`

Implements the specification-analysis port with Swagger Parser. It owns local specification loading, external-reference
guarding, schema normalization, and operation analysis.

### 5.5 `modules/adapters/filesystem`

Implements local artifact lifecycle ports:

- safe project writes;
- source snapshots and checksums;
- manifest and validation-report persistence;
- validation workspace copies;
- deterministic archive creation;
- portable and stable path identity policies.

### 5.6 `modules/adapters/validation`

Implements generated-project validation:

- target Java runtime resolution;
- bounded process execution;
- Gradle compile and application boot stages;
- MCP Streamable HTTP initialization, Tool listing, and Tool call verification;
- exact upstream request recording;
- loopback allocation and validation-host policy.

### 5.7 `modules/adapters/emitters/support`

Owns only framework-neutral emitter support:

- safe Java identifiers and literals;
- deterministic source-file and path models;
- framework-neutral schema and runtime source composition;
- shared Gradle wrapper assets.

The module must not import Spring AI or version-specific Jackson types and must not branch on a Spring AI family or
version. A responsibility that requires such a branch belongs in a version-specific emitter dialect.

### 5.8 Version-specific emitter modules

`spring-ai-1` and `spring-ai-2` independently own:

- compatibility-family checks;
- project scaffold and dependency rendering;
- callback, annotation, and SDK integration;
- Jackson 2 or Jackson 3 syntax;
- family-specific runtime dialect;
- generated-project golden and runtime smoke tests.

Both implement the same application output ports but do not share version-specific source through direct dependencies.

### 5.9 `modules/bootstrap`

Creates the canonical profile registry and composes use cases with concrete adapters. It replaces the current
`GeneratorApplication.defaults()` responsibility and contains no generation business rule.

### 5.10 `apps/cli` and `apps/web`

CLI owns command parsing, process exit, safe console output, and its SLF4J no-op provider. Web owns Boot MVC,
Thymeleaf pages, API controllers, job state, request security, and HTTP error mapping. Neither app constructs concrete
generators or validators directly.

The installed CLI executable remains `openapi-mcp`.

## 6. Java Package Hierarchy

```text
io.gen2spring.mcp
├── domain
│   ├── specification
│   ├── tool
│   ├── profile
│   ├── execution
│   ├── response
│   ├── observability
│   └── error
├── application
│   ├── command
│   ├── usecase
│   ├── planning
│   ├── toolmodel
│   │   ├── naming
│   │   ├── description
│   │   ├── security
│   │   └── output
│   ├── validation
│   └── port
│       ├── inbound
│       └── outbound
├── adapter
│   ├── configuration
│   ├── openapi
│   │   ├── source
│   │   └── swagger
│   ├── filesystem
│   │   ├── project
│   │   ├── archive
│   │   ├── checksum
│   │   ├── metadata
│   │   └── path
│   ├── validation
│   │   ├── gradle
│   │   ├── java
│   │   ├── process
│   │   ├── mcp
│   │   └── upstream
│   └── emitter
│       ├── support
│       │   ├── source
│       │   ├── schema
│       │   └── runtime
│       ├── springai1
│       │   ├── project
│       │   ├── source
│       │   └── runtime
│       └── springai2
│           ├── project
│           ├── source
│           └── runtime
├── bootstrap
└── app
    ├── cli
    │   ├── command
    │   ├── output
    │   └── error
    └── web
        ├── page
        ├── api
        ├── job
        ├── security
        ├── config
        └── error
```

A package is introduced only when it has a cohesive boundary or enough production types to improve navigation. Small
modules may keep cohesive types at their module-root package rather than creating one-class package chains.

### 6.1 Contract decomposition

The current `GenerationContracts` container is decomposed into top-level types owned by their actual layer. Proposed
application ports include:

```text
SpecificationAnalyzer
ProjectGenerator
ToolEmitter
GeneratedProjectValidator
ProjectWorkspace
ArtifactPackager
```

Tool IR is represented by focused domain types rather than framework subtypes:

```text
ToolDefinition
ToolInput
ToolOutput
ToolParameter
HttpExecution
```

The emitter relationship remains:

```text
ToolDefinition
      |
      v
ToolEmitter
├── SpringAi1ToolEmitter
├── SpringAi2ToolEmitter
└── McpJavaSdkToolEmitter  (future module and profile)
```

### 6.2 Responsibility-based class decomposition

Line count is a diagnostic signal, not a refactoring rule. A class is split only when one or more concrete boundaries are
proven:

- independent reasons to change;
- different abstraction levels or lifecycles;
- different library or security dependencies;
- separately testable failure policies;
- behavior duplicated across emitters;
- reduced coupling and public surface after extraction.

A long decision table that enforces one invariant may remain one class. Conversely, a short class that mixes unrelated
lifecycles should be split. Current large renderers, validators, configuration parsers, protocol clients, archive logic,
and Web job management are investigation candidates rather than automatically mandated splits.

## 7. Runtime Flow

```text
CLI / Web
    |
    +--> configuration adapter --> GenerationCommand
    |
    v
GenerateProject use case
    |
    +--> SpecificationAnalyzer --> OpenAPI adapter
    +--> Tool model policies  --> ToolDefinition list
    +--> ProjectGenerator     --> selected emitter adapter
    +--> ProjectWorkspace     --> filesystem adapter
    +--> Validator            --> validation adapter
    +--> ArtifactPackager     --> filesystem adapter
```

`PreviewProject` stops after the Tool IR and generated file list. It does not write the project, compile, boot, call MCP,
or package an archive.

Bootstrap participates only at startup by constructing this object graph. It does not become a runtime orchestration
layer.

## 8. Error, Security, and Compatibility Boundaries

- Domain owns canonical invariant failures and stable error codes.
- Application owns stage transitions and decides which remaining stages are skipped.
- Adapters translate external-library failures into safe application failures.
- CLI preserves current exit codes and stdout JSON.
- Web preserves current HTTP status and error envelope.
- Raw exception messages, local paths, process output, upstream response bodies, and secrets do not cross adapter
  boundaries unless an existing bounded public contract explicitly permits the data.
- Fatal `Error` identity, interrupt status, validation stage order, telemetry single-completion, response normalization,
  and secret masking remain unchanged.
- Spring component discovery must use explicit configuration or imports after package relocation; broad component scans
  must not accidentally register internal adapters.
- Generated package names and generated source imports are output contracts and are not renamed with repository packages.
- Service-provider resources, static Web assets, Thymeleaf templates, wrapper assets, and executable bits must retain
  their runtime behavior after relocation.

## 9. Migration Strategy

### Phase 1: Characterize the baseline

- record exact source paths and checksums for all four compatibility profiles;
- lock CLI exit, stdout, profile order, and installed executable behavior;
- lock Web routes and representative browser/API journeys;
- lock validation stages, error codes, Tool schema/result, and upstream-call count;
- compare against independent fixtures rather than comparing two production implementations.

### Phase 2: Establish domain and application

- move `generator-domain` to `modules/domain` and reorganize its packages;
- decompose `GenerationContracts` into layer-owned top-level types;
- create `modules/application`;
- move planning, pipeline, Tool policy, and validation-expectation planning into application;
- introduce outbound ports before moving concrete implementations;
- verify that application depends only on domain.

### Phase 3: Move infrastructure adapters

Move configuration, OpenAPI, filesystem, and validation adapters one at a time. Change the package and Gradle project
path in the same migration commit. Consolidate duplicated path or identity behavior only after tests prove that the
invariants are identical; otherwise retain separately named policies.

### Phase 4: Reorganize emitters

- move both version-specific emitter projects to their final paths and packages;
- extract only byte-equivalent, framework-neutral responsibilities into emitter support;
- represent family differences through version-owned dialects rather than version conditionals;
- move Gradle wrapper assets to one support-owned source;
- verify exact generated source path and byte parity after each extraction.

### Phase 5: Move bootstrap and apps

- replace `GeneratorApplication.defaults()` with explicit bootstrap composition;
- move CLI and Web to their final project and package paths;
- preserve explicit CLI application name and Boot entry-point configuration;
- update `settings.gradle.kts`, project dependencies, mise tasks, and CI task paths atomically.

### Phase 6: Remove the old hierarchy

The final tree must not contain old `generator-*` projects, old repository Java packages, deprecated forwarding classes,
or Gradle aliases. Documentation and build commands must refer only to the final hierarchy.

## 10. Verification Strategy

Every phase requires:

- focused unit tests for the moved responsibility;
- downstream compilation;
- `git diff --check`;
- scans for stale project paths and packages;
- verification of the allowed Gradle dependency direction;
- a clean process and temporary-resource readback for tests that boot generated applications.

Emitter migration additionally requires Java 17 and 21 source generation, exact generated source paths and checksums,
and representative Spring AI 1 and 2 generated-project compile/context tests.

Final local acceptance requires:

- all unit tests;
- installed CLI tests;
- all four profile generation journeys;
- generated compile, context, MCP protocol, Tool call, and exact upstream request verification;
- Web Boot integration and browser smoke tests;
- Windows and POSIX path behavior tests;
- deterministic archive verification using the existing measured-report normalization contract.

GitHub Actions continues to run the intentionally bounded fast structural and core checks. The expensive four-profile
boot and MCP matrix remains a required local pre-PR acceptance gate unless CI policy is separately changed.

## 11. Commit Boundaries

The implementation should use a small number of meaningful refactor commits rather than accumulating corrective
commits:

```text
refactor(structure): establish domain and application modules
refactor(adapters): reorganize generator infrastructure
refactor(emitters): separate shared rendering support
refactor(apps): reorganize CLI and web entry points
docs(build): align architecture and project paths
```

If a phase is too large to remain independently green, split it by a responsibility boundary, not by an arbitrary file
count. Do not use temporary `fix:` commits as the planned history structure.

## 12. Complexity and Risks

The mechanical file and import migration is linear in the number of affected files and references, `O(F + R)`. The
runtime algorithms, request counts, and generated-project space requirements do not change.

Primary risks and controls:

- **Spring scanning drift:** use explicit imports and context tests.
- **Generated-source drift:** compare path sets and bytes against independent baseline fixtures.
- **Resource lookup drift:** verify service providers, Web assets, wrapper hashes, and file modes.
- **Dependency cycles:** enforce the Gradle dependency direction after each phase.
- **Accidental empty Gradle modules:** keep intermediate hierarchy projects aggregation-only and apply Java conventions
  only to explicit leaf projects.
- **False sharing in emitter support:** prohibit framework imports and family/version branches.
- **Over-fragmentation:** introduce packages and classes only around cohesive, independently testable boundaries.
- **History loss:** use moves where practical and keep commits aligned with semantic migration boundaries.
- **Cross-platform path regression:** retain Windows and POSIX path tests and bounded process cleanup.

## 13. Completion Criteria

- The final physical and Gradle project trees match Section 4.
- The final Java packages match Section 6 without artificial one-class package chains.
- Production dependency direction matches Section 4.2 with no cycles or peer-adapter coupling.
- `GenerationContracts` no longer acts as a mixed contract container.
- CLI and Web share the same bootstrapped application use cases.
- Spring AI 1 and 2 emitters remain isolated and preserve generated output behavior.
- No old module name, old internal package, compatibility shim, or duplicate wrapper asset remains.
- All verification gates in Section 10 pass with no new skipped test.
- README, PRD architecture, design documents, Gradle commands, mise tasks, and CI paths describe the final structure.

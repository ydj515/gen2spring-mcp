# Spring Boot Thymeleaf Web Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate `generator-web` from the JDK `HttpServer` to a loopback-only Spring Boot 3.5.16 WebMVC, Thymeleaf, and Spring Security application while preserving the existing editor and Generator API semantics.

**Architecture:** Spring Boot owns the server, servlet lifecycle, Jackson 2 mapper, security chain, Thymeleaf shell, and static resources. Existing generator application services, specification storage, bounded job manager, and fixed error mapper remain the semantic source of truth behind focused MVC controllers. Local mode remains unauthenticated but loopback-only with exact Host/Origin checks and Spring Security CSRF; public multi-user hosting remains explicitly unsupported.

**Tech Stack:** Java 21, Gradle 9.6.1, Spring Boot 3.5.16, Spring WebMVC, Thymeleaf, Spring Security, Jackson 2.22.0, JUnit 5.13.4, MockMvc, JDK HttpClient

## Global Constraints

- Bind the actual listener to numeric IPv4 `127.0.0.1`; hostile `server.address` property overrides must not open a remote listener.
- Preserve the existing `/api/**` routes, success statuses, bounded bodies, opaque identifiers, artifact allow-list, media type and charset semantics, and fixed non-leaking error envelope; servlet-normalized optional header whitespace is not byte-significant.
- Replace the per-process capability header with Spring Security session CSRF; keep exact same-origin Origin checks on state-changing requests.
- Reject `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Port`, `X-Forwarded-Proto`, and `X-Real-IP` before reading request bodies.
- Keep `GeneratorApplication`, `SpecificationStore`, and `GenerationJobManager` behavior and bounds unchanged.
- Preserve stdout as one bounded `READY` JSON line; route Boot and application logs to stderr.
- Exclude legacy `commons-logging` from the Web runtime and use Spring's `spring-jcl` bridge only.
- Keep the current five-step editor, accessibility contract, framework-free ES modules, and 400px layout.
- Do not add accounts, database, tenant ownership, remote bind, reverse-proxy trust, distributed queue, external storage, URL import, React, or Node tooling.
- Do not commit, stage, push, or open a PR unless the user explicitly authorizes it.

---

## File Structure

### Create

- `generator-web/src/main/java/io/gen2spring/mcp/web/Gen2SpringWebApplication.java` — Boot entry point only.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebRuntimeConfiguration.java` — canonical application/store/job beans and loopback server customizer.
- `generator-web/src/main/java/io/gen2spring/mcp/web/LocalRequestSecurityFilter.java` — exact remote/Host/Origin/forwarded-header checks.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebSecurityConfiguration.java` — permit-all local chain, CSRF, fixed security failures, headers.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorResponseWriter.java` — fixed JSON envelope writer shared by servlet/security boundaries.
- `generator-web/src/main/java/io/gen2spring/mcp/web/EditorController.java` — Thymeleaf shell and CSRF model.
- `generator-web/src/main/java/io/gen2spring/mcp/web/ProfileController.java` — profiles endpoint.
- `generator-web/src/main/java/io/gen2spring/mcp/web/SpecificationController.java` — upload and preview endpoints.
- `generator-web/src/main/java/io/gen2spring/mcp/web/GenerationJobController.java` — job create/read/delete endpoints.
- `generator-web/src/main/java/io/gen2spring/mcp/web/ArtifactController.java` — artifact downloads.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebApiExceptionHandler.java` — controller exception mapping.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorController.java` — servlet `/error` fallback for `/api/**`.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebMvcConfiguration.java` — Jackson response charset compatibility.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebContentTypes.java` — exact bounded request media-type allow-lists.
- `generator-web/src/main/java/io/gen2spring/mcp/web/ReadyReporter.java` — exact stdout readiness line.
- `generator-web/src/main/resources/templates/editor.html` — Thymeleaf page shell.
- `generator-web/src/main/resources/static/*` — existing CSS/JS/favicon.
- `generator-web/src/main/resources/application.yml` — safe Boot defaults.
- `generator-web/src/main/resources/logback-spring.xml` — stderr logging.
- `generator-web/src/test/java/io/gen2spring/mcp/web/WebApplicationContextTest.java` — application/lifecycle contract.
- `generator-web/src/test/java/io/gen2spring/mcp/web/LocalRequestSecurityFilterTest.java` — local request boundary.
- `generator-web/src/test/java/io/gen2spring/mcp/web/WebMvcContractTest.java` — root/API/error/security contract.

### Modify

- `generator-web/build.gradle.kts` — Boot dependencies/tasks and bootJar integration test input.
- `generator-web/src/main/java/io/gen2spring/mcp/web/PreviewHandler.java` — expose transport-neutral service methods as beans without semantic change.
- `generator-web/src/main/java/io/gen2spring/mcp/web/JobHandler.java` — expose transport-neutral service methods as beans without semantic change.
- `generator-web/src/main/java/io/gen2spring/mcp/web/ArtifactHandler.java` — expose transport-neutral service methods as beans without semantic change.
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorMapper.java` — map servlet/Spring boundary failures to fixed values.
- `generator-web/src/main/resources/static/api.js` — read CSRF metadata and send it only for unsafe methods.
- `generator-web/src/test/java/io/gen2spring/mcp/web/StaticAssetContractTest.java` — new resource paths and CSRF metadata.
- `generator-web/src/test/java/io/gen2spring/mcp/web/ReadmeContractTest.java` — Boot/mise commands and hosted-mode warning.
- `generator-web/src/integrationTest/java/io/gen2spring/mcp/web/LocalOperationEditorIntegrationTest.java` — launch bootJar, preserve cookie/CSRF, run the real journey.
- `mise.toml` — add `ui`, `ui:build`, and `ui:test` Boot tasks.
- `README.md` — Boot UI startup and local-only boundary.

### Delete after parity is green

- `generator-web/src/main/java/io/gen2spring/mcp/web/JsonHttp.java`
- `generator-web/src/main/java/io/gen2spring/mcp/web/LocalWebServer.java`
- `generator-web/src/main/java/io/gen2spring/mcp/web/Main.java`
- `generator-web/src/main/java/io/gen2spring/mcp/web/RequestGuard.java`
- `generator-web/src/main/java/io/gen2spring/mcp/web/StaticAssetHandler.java`
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebApplicationFactory.java`
- `generator-web/src/main/java/io/gen2spring/mcp/web/WebArguments.java`
- `generator-web/src/test/java/io/gen2spring/mcp/web/LocalWebServerTest.java`
- `generator-web/src/test/java/io/gen2spring/mcp/web/RequestGuardTest.java`

---

### Task 1: Boot executable and canonical lifecycle

**Files:**
- Modify: `generator-web/build.gradle.kts`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/Gen2SpringWebApplication.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebRuntimeConfiguration.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/ReadyReporter.java`
- Create: `generator-web/src/main/resources/application.yml`
- Create: `generator-web/src/main/resources/logback-spring.xml`
- Test: `generator-web/src/test/java/io/gen2spring/mcp/web/WebApplicationContextTest.java`

**Interfaces:**
- Consumes: `GeneratorApplication.defaults()`, `SpecificationStore(Path, SpecificationAnalyzer)`, `GenerationJobManager(...)`, `WebApplicationFactory.privateTemporaryParent()` behavior.
- Produces: Boot main class `Gen2SpringWebApplication`; beans `GeneratorApplication`, `SpecificationStore`, `GenerationJobManager`, `Clock`; loopback-only `WebServerFactoryCustomizer<org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory>`; application-ready stdout JSON.

- [x] **Step 1: Write the failing build and context tests**

Add assertions that load `application.yml`, start a random-port context, verify the actual address, and verify canonical bean identity:

```java
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=0.0.0.0")
class WebApplicationContextTest {
    @Autowired GeneratorApplication application;
    @Autowired SpecificationStore specifications;
    @Autowired GenerationJobManager jobs;
    @Autowired ServletWebServerApplicationContext context;

    @Test
    void startsOnlyOnNumericLoopbackWithCanonicalOwnedServices() {
        assertSame(application, context.getBean(GeneratorApplication.class));
        assertEquals("127.0.0.1", context.getWebServer().getAddress().getHostAddress());
        assertTrue(context.getWebServer().getPort() > 0);
        assertTrue(specifications.root().startsWith(Path.of(System.getProperty("java.io.tmpdir"))));
        assertTrue(jobs.root().startsWith(Path.of(System.getProperty("java.io.tmpdir"))));
    }
}
```

Add a build-source test or direct Gradle assertions for exact Boot 3.5.16 dependencies:

```kotlin
plugins {
    id("org.springframework.boot") version "3.5.16"
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
```

- [x] **Step 2: Run the focused RED**

Run:

```bash
mise exec -- ./gradlew :generator-web:compileTestJava \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: FAIL because the Boot application/configuration classes and dependencies do not exist.

- [x] **Step 3: Implement the minimal Boot composition**

Create the entry point:

```java
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class Gen2SpringWebApplication {
    public static void main(String[] arguments) {
        SpringApplication.run(Gen2SpringWebApplication.class, arguments);
    }
}
```

Create lifecycle beans and force loopback after property binding:

```java
@Configuration(proxyBeanMethods = false)
class WebRuntimeConfiguration {
    @Bean
    GeneratorApplication generatorApplication() {
        return GeneratorApplication.defaults();
    }

    @Bean
    Clock webClock() {
        return Clock.systemUTC();
    }

    @Bean
    Path privateTemporaryParent() {
        return VerifiedTemporaryParent.resolve();
    }

    @Bean(destroyMethod = "close")
    SpecificationStore specificationStore(Path temporaryParent, GeneratorApplication application) {
        return new SpecificationStore(temporaryParent, application.analyzer());
    }

    @Bean(destroyMethod = "close")
    GenerationJobManager generationJobManager(
            Path temporaryParent, GeneratorApplication application, Clock clock) {
        return new GenerationJobManager(
                temporaryParent, application.pipeline()::generate, clock,
                Duration.ofHours(1), ignored -> {});
    }

    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> loopbackOnly() {
        return factory -> factory.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    }
}
```

Move the safe temp-parent resolution from `WebApplicationFactory` into a focused helper or private configuration method; keep the fixed `WORKSPACE_CREATE_FAILED` error.

Configure safe defaults:

```yaml
server:
  address: 127.0.0.1
  port: ${GEN2SPRING_UI_PORT:0}
  forward-headers-strategy: none
  error:
    include-message: never
    include-binding-errors: never
    include-exception: false
    whitelabel:
      enabled: false
  servlet:
    session:
      cookie:
        http-only: true
        same-site: strict
spring:
  main:
    banner-mode: "off"
```

Make `ReadyReporter` ignore non-server test contexts, obtain the actual `WebServer` port from a
`ServletWebServerApplicationContext`, and print exactly:

```java
System.out.println("{\"status\":\"READY\",\"url\":\"http://127.0.0.1:"
        + port + "/\"}");
```

Configure one Logback console appender with `<target>System.err</target>` and no stdout appender.

- [x] **Step 4: Run the focused GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-web:test \
  --tests 'io.gen2spring.mcp.web.WebApplicationContextTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS; actual listener address is `127.0.0.1`, hostile property is ignored, and context closes owned beans.

- [x] **Step 5: Review checkpoint**

Check `git diff --check`, inspect only Task 1 files, and do not stage or commit without explicit user authorization.

---

### Task 2: Local request and Spring Security boundary

**Files:**
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/LocalRequestSecurityFilter.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebSecurityConfiguration.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorResponseWriter.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorMapper.java`
- Test: `generator-web/src/test/java/io/gen2spring/mcp/web/LocalRequestSecurityFilterTest.java`

**Interfaces:**
- Consumes: `WebErrorMapper.WebFailure`, Boot-managed `ObjectMapper`, servlet request local/remote address and port.
- Produces: `SecurityFilterChain localSecurity(...)`, `LocalRequestSecurityFilter`, fixed JSON writer used by CSRF/access-denied and local-boundary failures.

- [x] **Step 1: Write failing filter and CSRF tests**

Use MockMvc to prove exact acceptance and rejection:

```java
mockMvc.perform(get("/").header("Host", "127.0.0.1:" + port))
        .andExpect(status().isOk());

mockMvc.perform(post("/api/specifications")
        .header("Host", "127.0.0.1:" + port)
        .header("Origin", "http://127.0.0.1:" + port))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("REQUEST_FORBIDDEN"));

mockMvc.perform(get("/")
        .header("Host", "localhost:" + port))
        .andExpect(status().isForbidden());

for (String header : List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host",
        "X-Forwarded-Port", "X-Forwarded-Proto", "X-Real-IP")) {
    mockMvc.perform(get("/").header("Host", "127.0.0.1:" + port).header(header, "private-marker"))
            .andExpect(status().isForbidden())
            .andExpect(content().string(not(containsString("private-marker"))));
}
```

Add direct filter tests with `remoteAddr=127.0.0.2`, duplicate Host/Origin, missing Origin, and wrong port.

- [x] **Step 2: Run the focused RED**

Run:

```bash
mise exec -- ./gradlew :generator-web:test \
  --tests 'io.gen2spring.mcp.web.LocalRequestSecurityFilterTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: FAIL because Spring Security/filter/error writer do not exist.

- [x] **Step 3: Implement the minimal local security chain**

Implement `LocalRequestSecurityFilter` as `OncePerRequestFilter`. It must compare:

```java
String expectedHost = "127.0.0.1:" + request.getLocalPort();
boolean changing = !Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod());
String expectedOrigin = "http://" + expectedHost;
```

Require exactly one Host value, no reserved forwarding header, exact remote `127.0.0.1`, and exact Origin for changing requests. On failure call `WebErrorResponseWriter.write(response, mapper.map(new RequestRejectedException()))` and return without calling the chain.

Configure Spring Security:

```java
http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .exceptionHandling(errors -> errors.accessDeniedHandler(
                (request, response, failure) -> writer.write(response, forbidden)))
        .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
        .addFilterBefore(localRequestSecurityFilter, CsrfFilter.class);
```

Do not disable CSRF, create a user, enable form login, HTTP Basic, remember-me, or external authentication.

- [x] **Step 4: Run the focused GREEN**

Run the Task 2 test class and `WebApplicationContextTest`. Expected: all accepted/rejected cases return fixed JSON and no rejected value appears in body/log.

- [x] **Step 5: Review checkpoint**

Search production/test result files for `private-marker`, forwarded header values, stack traces, and generated passwords. Do not stage or commit.

---

### Task 3: Thymeleaf shell and CSRF-aware browser client

**Files:**
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/EditorController.java`
- Create: `generator-web/src/main/resources/templates/editor.html`
- Move: `generator-web/src/main/resources/web/styles.css` -> `generator-web/src/main/resources/static/styles.css`
- Move: `generator-web/src/main/resources/web/app.js` -> `generator-web/src/main/resources/static/app.js`
- Move: `generator-web/src/main/resources/web/api.js` -> `generator-web/src/main/resources/static/api.js`
- Move: `generator-web/src/main/resources/web/state.js` -> `generator-web/src/main/resources/static/state.js`
- Move: `generator-web/src/main/resources/web/editor.js` -> `generator-web/src/main/resources/static/editor.js`
- Move: `generator-web/src/main/resources/web/favicon.svg` -> `generator-web/src/main/resources/static/favicon.svg`
- Modify: `generator-web/src/test/java/io/gen2spring/mcp/web/StaticAssetContractTest.java`
- Test: `generator-web/src/test/java/io/gen2spring/mcp/web/WebMvcContractTest.java`

**Interfaces:**
- Consumes: Spring `CsrfToken`; existing five-step HTML/CSS/JS.
- Produces: `GET /` Thymeleaf view with meta names `csrf-token` and `csrf-header`; unsafe browser requests with exact CSRF header.

- [x] **Step 1: Write the failing template/static tests**

Assert the rendered page, not only the source template:

```java
MvcResult result = mockMvc.perform(get("/")
        .header("Host", "127.0.0.1:" + port))
        .andExpect(status().isOk())
        .andExpect(view().name("editor"))
        .andExpect(content().string(containsString("<h2>1. Specification</h2>")))
        .andExpect(content().string(containsString("name=\"csrf-token\"")))
        .andExpect(content().string(containsString("name=\"csrf-header\"")))
        .andReturn();
assertFalse(result.getResponse().getContentAsString().contains("__GEN2SPRING_TOKEN__"));
```

Update the static contract to read `/templates/editor.html` and `/static/*.js`; assert `api.js` contains the CSRF meta names and no `X-Gen2Spring-Token`/`generator-api-token`.

- [x] **Step 2: Run the focused RED**

Run `StaticAssetContractTest` and the root-render method in `WebMvcContractTest`. Expected: FAIL on missing template/controller/CSRF metadata.

- [x] **Step 3: Implement the minimal template migration**

Controller:

```java
@Controller
final class EditorController {
    @GetMapping("/")
    String editor(CsrfToken csrf, Model model) {
        model.addAttribute("csrfToken", csrf.getToken());
        model.addAttribute("csrfHeader", csrf.getHeaderName());
        return "editor";
    }
}
```

Template head:

```html
<meta name="csrf-token" th:content="${csrfToken}">
<meta name="csrf-header" th:content="${csrfHeader}">
```

Update `api.js`:

```javascript
const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content ?? '';
const csrfHeader = document.querySelector('meta[name="csrf-header"]')?.content ?? '';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

async function request(path, options = {}) {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers(options.headers ?? {});
  if (!SAFE_METHODS.has(method)) headers.set(csrfHeader, csrfToken);
  return fetch(path, {...options, method, headers, cache: 'no-store'});
}
```

Keep all existing five-step body markup and accessibility attributes byte-equivalent except Thymeleaf/CSRF metadata.

- [x] **Step 4: Run the focused GREEN**

Run `StaticAssetContractTest` and the root-render test. Expected: PASS with no inline JS/style or external URL.

- [x] **Step 5: Review checkpoint**

Compare all old and new static assets; only resource path and CSRF handling may differ. Do not stage or commit.

---

### Task 4: MVC Generator API and fixed failure mapping

**Files:**
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/ProfileController.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/SpecificationController.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/GenerationJobController.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/ArtifactController.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebApiExceptionHandler.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorController.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/PreviewHandler.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/JobHandler.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/ArtifactHandler.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorMapper.java`
- Test: `generator-web/src/test/java/io/gen2spring/mcp/web/WebMvcContractTest.java`

**Interfaces:**
- Consumes: `PreviewHandler.profiles/upload/preview`, `JobHandler.start/status/delete`, `ArtifactHandler.download`, `WebErrorMapper.map`.
- Produces: exact existing `/api/**` MVC endpoints and fixed error responses.

- [x] **Step 1: Write failing API parity tests**

Port the semantic assertions from `LocalWebServerTest` to MockMvc. Use `with(csrf())`, exact Host, and Origin:

```java
mockMvc.perform(post("/api/specifications")
        .with(csrf())
        .header("Host", host)
        .header("Origin", origin)
        .header("X-Specification-Name", "weather.yaml")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .content(specification()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.operations[0].operationId").value("getForecast"));
```

Test every route/status plus invalid JSON, duplicate JSON keys, oversized upload/configuration, unsafe name, invalid ID, missing artifact, wrong method/content type, unknown route, CSRF failure, and an unexpected `RuntimeException`. Assert each failure has exactly `error.code`, `error.stage`, `error.message` and never contains a private marker, exception type/message, path, body, or stack.

- [x] **Step 2: Run the focused RED**

Run `WebMvcContractTest`. Expected: root may pass from Task 3 but `/api/**` routes are 404 or default Spring errors.

- [x] **Step 3: Implement the minimal controllers**

Use response types that preserve status and content headers:

```java
@RestController
final class SpecificationController {
    @PostMapping(path = "/api/specifications", consumes = {
            MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE,
            "application/yaml", "text/yaml"})
    ResponseEntity<JsonNode> upload(
            @RequestHeader("X-Specification-Name") String name,
            HttpServletRequest request) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(previews.upload(name, request.getInputStream()));
    }

    @PostMapping(path = "/api/specifications/{id}/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    JsonNode preview(@PathVariable String id, HttpServletRequest request) throws IOException {
        requireOpaqueId(id);
        return previews.preview(id, request.getInputStream());
    }
}
```

Keep all body reading inside existing bounded readers. Do not bind arbitrary request bodies into unbounded `String`, `byte[]`, or generic DTO first.

Artifact responses must bypass `HttpMessageConverter` media-type normalization and write the already bounded
artifact bytes with exact content headers:

```java
byte[] bytes = download.bytes();
response.setStatus(HttpServletResponse.SC_OK);
response.setHeader(HttpHeaders.CONTENT_TYPE, download.contentType());
response.setHeader(HttpHeaders.CONTENT_DISPOSITION, download.contentDisposition());
response.setContentLength(bytes.length);
response.getOutputStream().write(bytes);
```

`WebApiExceptionHandler` catches `Exception`, not `Throwable` or `Error`. It maps through `WebErrorMapper`:

```java
@RestControllerAdvice
final class WebApiExceptionHandler {
    private final WebErrorMapper mapper;
    private final WebErrorResponseWriter writer;

    @ExceptionHandler(Exception.class)
    ResponseEntity<ObjectNode> failure(Exception failure) {
        WebFailure mapped = mapper.map(failure);
        return ResponseEntity.status(mapped.status()).body(writer.body(mapped));
    }
}
```

Add explicit exception methods or mapper branches for Spring media/method/missing-header/route exceptions with fixed values. `WebErrorController` handles servlet `/error` without returning the Whitelabel/default attribute map:

```java
@RestController
final class WebErrorController implements ErrorController {
    @RequestMapping("/error")
    ResponseEntity<ObjectNode> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer code && code >= 400 && code <= 599 ? code : 500;
        WebFailure failure = mapper.httpStatus(status);
        return ResponseEntity.status(failure.status()).body(writer.body(failure));
    }
}
```

`WebErrorResponseWriter.body(WebFailure)` constructs the same three-field JSON object used by its servlet response writer. Add `WebErrorMapper.httpStatus(int)` as a finite switch for 400, 403, 404, 405, 413, 415, and default 500; every message is a fixed literal.

- [x] **Step 4: Run the focused GREEN**

Run `WebMvcContractTest`, `PreviewHandler`/`JobHandler`/`ArtifactHandler` existing tests, and `GenerationJobManagerTest`. Expected: exact API parity and unchanged core lifecycle.

- [x] **Step 5: Review checkpoint**

Diff the old `LocalWebServer.route` table against controller annotations line by line. Confirm no path/status/content type/error mapping is missing. Do not stage or commit.

---

### Task 5: bootJar integration and obsolete transport removal

**Files:**
- Modify: `generator-web/build.gradle.kts`
- Modify: `generator-web/src/integrationTest/java/io/gen2spring/mcp/web/LocalOperationEditorIntegrationTest.java`
- Delete: manual server/application/security/static transport classes and their two tests listed in File Structure.
- Test: `generator-web/src/test/java/io/gen2spring/mcp/web/WebApplicationContextTest.java`

**Interfaces:**
- Consumes: Boot `bootJar`, READY stdout, session cookie and CSRF meta values, existing real generation fixtures.
- Produces: real packaged-server acceptance and zero `com.sun.net.httpserver` production references.

- [x] **Step 1: Write the failing bootJar integration harness**

Change Gradle integration input from `installDist` to the `BootJar` archive:

```kotlin
val bootJar = tasks.named<BootJar>("bootJar")

testTask.configure {
    dependsOn(bootJar)
    systemProperty("gen2springWeb.bootJar", bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
```

Start it with target Java 21:

```java
new ProcessBuilder(
        Path.of(java21Home, "bin", "java").toString(),
        "-Djava.io.tmpdir=" + runtimeTemp,
        "-jar", bootJar.toString(),
        "--server.port=0")
```

Parse stdout as exactly one nonblank READY JSON line. Read stderr separately with a 1 MiB bound.

Obtain `Set-Cookie` and CSRF meta values from root HTML. Send the cookie on all later requests and the rendered CSRF header/token on POST/DELETE. Keep all existing profile, upload, preview, job, artifact, archive, non-leak, process-tree, and port-release assertions.

- [x] **Step 2: Run the integration RED**

Run:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew :generator-web:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: FAIL until bootJar, READY, cookie/CSRF, and controllers are complete.

- [x] **Step 3: Make the real packaged journey GREEN**

Fix only mismatches against the approved design. Do not weaken exact schema/result/artifact/non-leak assertions. Confirm stdout has one READY line, logs are only stderr, and session/CSRF values do not appear in generated artifacts or failure messages.

- [x] **Step 4: Delete obsolete manual transport**

Delete the seven manual production classes and two tests only after the real Boot journey is green. Add a source contract:

```java
assertTrue(Files.walk(mainJava)
        .filter(path -> path.toString().endsWith(".java"))
        .map(Files::readString)
        .noneMatch(source -> source.contains("com.sun.net.httpserver")));
```

Run all `generator-web:test` and `integrationTest` again. Expected: PASS and `rg 'com\.sun\.net\.httpserver|X-Gen2Spring-Token|generator-api-token' generator-web/src/main` returns no matches.

- [x] **Step 5: Review checkpoint**

Check process cleanup, port release, stdout/stderr bounds, and fatal `Error` behavior. Do not stage or commit.

---

### Task 6: mise, README, browser contract, and final verification

**Files:**
- Modify: `mise.toml`
- Modify: `README.md`
- Modify: `generator-web/src/test/java/io/gen2spring/mcp/web/ReadmeContractTest.java`
- Modify: `generator-web/src/test/java/io/gen2spring/mcp/web/StaticAssetContractTest.java`
- Modify: `docs/superpowers/specs/2026-08-12-spring-boot-thymeleaf-web-migration-design.md` only for implementation-proven corrections.

**Interfaces:**
- Consumes: `:generator-web:bootRun`, `:generator-web:bootJar`, `:generator-web:test`, READY URL.
- Produces: documented and executable local UI workflow with an explicit hosted-mode gate.

- [x] **Step 1: Write failing mise/README contract tests**

Assert exact commands and absence of obsolete packaging:

```java
assertTrue(readme.contains("mise run ui"));
assertTrue(readme.contains("mise run ui:build"));
assertTrue(readme.contains("mise run ui:test"));
assertTrue(readme.contains("./gradlew :generator-web:bootRun"));
assertTrue(readme.contains("./gradlew :generator-web:bootJar"));
assertTrue(readme.contains("java -jar generator-web/build/libs/"));
assertTrue(readme.contains("public multi-user service가 아니다"));
assertFalse(readme.contains(":generator-web:installDist"));
assertFalse(readme.contains("X-Gen2Spring-Token"));
```

Assert `mise.toml` contains Windows and POSIX tasks using `bootRun`, `bootJar`, and `test`, plus both target JDK homes and `GEN2SPRING_UI_PORT`.

- [x] **Step 2: Run the focused RED**

Run `ReadmeContractTest` and `mise tasks validate`. Expected: FAIL because current docs/tasks still describe `installDist` or lack Boot tasks.

- [x] **Step 3: Implement the Boot mise tasks and documentation**

Use:

```toml
[tasks.ui]
description = "Run the local Spring Boot operation editor"
run = '''
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17)"
export GEN2SPRING_JAVA_21_HOME="$(mise where java@21)"
exec ./gradlew :generator-web:bootRun --quiet --no-daemon --non-interactive
'''
run_windows = '''
for /f "delims=" %i in ('mise where java@17') do @set "GEN2SPRING_JAVA_17_HOME=%i"
for /f "delims=" %i in ('mise where java@21') do @set "GEN2SPRING_JAVA_21_HOME=%i"
call gradlew.bat :generator-web:bootRun --quiet --no-daemon --non-interactive
'''

[tasks."ui:build"]
run = "./gradlew :generator-web:bootJar --no-daemon --non-interactive"
run_windows = ".\\gradlew.bat :generator-web:bootJar --no-daemon --non-interactive"

[tasks."ui:test"]
run = "./gradlew :generator-web:test --no-daemon --non-interactive --rerun-tasks"
run_windows = ".\\gradlew.bat :generator-web:test --no-daemon --non-interactive --rerun-tasks"
```

Document `GEN2SPRING_UI_PORT=8080 mise run ui`, direct `bootRun`, `bootJar`, and `java -jar`. State that loopback local mode is not a public multi-user deployment; list authentication, tenancy, database, durable storage, queue, proxy trust, audit/quota as the hosted gate.

- [x] **Step 4: Run focused and browser acceptance**

Run:

```bash
mise tasks validate
mise run ui:test
GEN2SPRING_UI_PORT=18765 mise run ui
```

Against the READY URL verify desktop and 400px browser journeys: root render, upload through preview, keyboard focus order, no horizontal overflow, no console error, no external network request. Stop the task and verify port 18765 is released.

- [x] **Step 5: Run full generator-web verification**

Run:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew :generator-web:test :generator-web:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

Then run the repository fast gate:

```bash
mise exec -- ./gradlew \
  :generator-domain:test :generator-openapi:test :generator-policy:test \
  :generator-core:test :generator-application:test :generator-cli:installDist \
  :generator-web:classes --no-daemon --non-interactive --rerun-tasks
```

Expected: all tasks pass, no test failures/errors, no lingering application/Gradle child process attributable to the test, and `git diff --check` is clean.

- [x] **Step 6: Final review checkpoint**

Review the complete diff against every design completion condition. Scan tracked source and successful test reports for secrets, CSRF tokens, private paths, stack traces, `com.sun.net.httpserver`, old capability header, and public-hosting overclaims. Report results and changed files; do not stage, commit, push, or open a PR without explicit authorization.

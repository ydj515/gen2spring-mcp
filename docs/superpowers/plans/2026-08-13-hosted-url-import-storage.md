# Hosted URL Import and Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store specifications/artifacts in private S3-compatible storage and import public OpenAPI URLs through an SSRF-resistant gateway.

**Architecture:** Application ports own object and fetch contracts. A private gateway resolves and validates destinations before connecting; a trusted per-job import-runner can reach only that gateway, parses the bounded result, and publishes only valid OpenAPI sources.

**Tech Stack:** Java 21, Spring Boot 3.5.16, AWS SDK v2 S3 adapter, Apache HttpClient 5 gateway, MinIO, Testcontainers.

## Global Constraints

- Web never performs network fetch or receives object-storage credentials with bucket-admin scope.
- Bucket is private; object key never contains owner input or original filename.
- Gateway allows HTTP/HTTPS ports 80/443, 3 redirects, connect 5 seconds, total 30 seconds.
- Reject every hostname if any resolved address is loopback/private/link-local/multicast/reserved/ULA/metadata.
- Bound wire and decompressed bodies independently to 10 MiB.
- Do not log URL host/path/query, response body, owner, or DNS answers.

---

### Task 1: Object-storage port and private S3 adapter

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/storage/ObjectStorage.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/storage/ObjectKey.java`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `modules/adapters/object-storage-s3/build.gradle.kts`
- Create: `modules/adapters/object-storage-s3/src/main/java/io/gen2spring/mcp/adapter/storage/S3ObjectStorage.java`
- Test: `modules/adapters/object-storage-s3/src/test/java/io/gen2spring/mcp/adapter/storage/S3ObjectStorageTest.java`

**Interfaces:** Implements the master `ObjectStorage` interface with exact-size/checksum writes and bounded reads.

- [ ] Write MinIO integration tests for private bucket policy, put/get/delete, checksum mismatch, oversize, missing object, restart persistence, and object-key validation.
- [ ] Confirm RED before adapter implementation.
- [ ] Add the S3-compatible client behind the application port; configure path-style access only in the adapter.
- [ ] Run focused/full module tests GREEN and commit as `feat(storage): persist private hosted artifacts`.

### Task 2: Destination policy

**Files:**
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/imports/ImportTarget.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/imports/NetworkAddressPolicy.java`
- Test: `modules/domain/src/test/java/io/gen2spring/mcp/domain/platform/imports/NetworkAddressPolicyTest.java`

**Interfaces:** Produces strict URL syntax and `requirePublicDestination(List<InetAddress>)` without I/O.

- [ ] Write table-driven tests for IPv4/IPv6 literals, mapped IPv4, loopback, RFC1918, link-local, multicast, documentation/reserved ranges, ULA, metadata aliases, mixed public/private answers, ports, userinfo, and fragments.
- [ ] Confirm RED, implement finite classification, then run full domain tests GREEN.
- [ ] Commit as `feat(domain): define safe URL import policy`.

### Task 3: Import-target envelope encryption

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/imports/ImportTargetProtector.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/imports/EncryptedImportTarget.java`
- Modify: `settings.gradle.kts`
- Create: `modules/adapters/cryptography/build.gradle.kts`
- Create: `modules/adapters/cryptography/src/main/java/io/gen2spring/mcp/adapter/cryptography/AesGcmImportTargetProtector.java`
- Test: `modules/adapters/cryptography/src/test/java/io/gen2spring/mcp/adapter/cryptography/AesGcmImportTargetProtectorTest.java`

**Interfaces:** Implements `ImportTargetProtector` with a random per-target AES-256-GCM data key wrapped by an operator file-secret key identified by a bounded key ID.

- [ ] Write tests for nondeterministic ciphertext, round trip, modified nonce/ciphertext/wrapped key, wrong/retired key, missing key file, no plaintext retention, and fixed non-leaking failures.
- [ ] Confirm RED, implement a versioned immutable envelope using JDK cryptography, and keep key material out of environment variables.
- [ ] Run focused/full adapter tests GREEN and commit as `feat(security): protect hosted import targets`.

### Task 4: Internal fetch gateway and per-job runner

**Files:**
- Modify: `settings.gradle.kts`
- Create: `apps/fetch-gateway/build.gradle.kts`
- Create: `apps/fetch-gateway/src/main/java/io/gen2spring/mcp/app/fetch/FetchGatewayApplication.java`
- Create: `apps/fetch-gateway/src/main/java/io/gen2spring/mcp/app/fetch/BoundedFetcher.java`
- Create: `apps/fetch-gateway/src/main/java/io/gen2spring/mcp/app/fetch/ValidatedDnsResolver.java`
- Create: `apps/fetch-gateway/src/main/java/io/gen2spring/mcp/app/fetch/FetchController.java`
- Test: `apps/fetch-gateway/src/test/java/io/gen2spring/mcp/app/fetch/FetchGatewaySecurityTest.java`
- Create: `apps/import-runner/build.gradle.kts`
- Create: `apps/import-runner/src/main/java/io/gen2spring/mcp/app/importer/ImportRunner.java`
- Test: `apps/import-runner/src/test/java/io/gen2spring/mcp/app/importer/ImportRunnerTest.java`

**Interfaces:** Gateway accepts only mTLS-authenticated requests from the import-runner network and returns status/media type plus a bounded body. Resolver validates all addresses and supplies only the validated address set to the connection manager. Import-runner accepts the decrypted URL through a read-only tmpfs input file, calls only the gateway, validates OpenAPI, and writes one bounded source result.

- [ ] Write adversarial tests for redirect-to-private, public/private dual answer, re-resolution mutation, IP literals, TLS hostname verification, header bounds, compression oversize, redirect limit, timeout, and error redaction.
- [ ] Confirm RED before gateway classes exist.
- [ ] Implement manual redirect handling and disabled automatic decompression; count wire bytes before bounded decompression. Configure mutual TLS and disable request access logging.
- [ ] Add import-runner tests proving it has no direct egress route, rejects mutable/symlink input, and emits no URL or response body in output/logs.
- [ ] Run gateway tests GREEN and commit as `feat(fetch): isolate URL import egress`.

### Task 5: Import orchestration

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/imports/UrlFetchClient.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/imports/SpecificationImportService.java`
- Create: `modules/adapters/url-fetch/build.gradle.kts`
- Create: `modules/adapters/url-fetch/src/main/java/io/gen2spring/mcp/adapter/urlfetch/GatewayUrlFetchClient.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/imports/SpecificationImportServiceTest.java`
- Test: `modules/adapters/url-fetch/src/test/java/io/gen2spring/mcp/adapter/urlfetch/UrlImportIntegrationTest.java`

**Interfaces:** Consumes `UrlFetchClient`, existing `SpecificationAnalyzer`, `ObjectStorage`, and persistence ports. Worker launches the trusted import-runner on the gateway-only network. The service produces owner-scoped `SpecificationId` only after strict OpenAPI analysis and object publication.

- [ ] Write tests for immutable import retry, invalid OpenAPI, storage failure cleanup, terminal encrypted-target cleanup, duplicate idempotency, and safe events.
- [ ] Confirm RED and implement the orchestration without HTTP framework imports.
- [ ] Run gateway, MinIO, PostgreSQL, and application integration GREEN.
- [ ] Commit as `feat(application): import hosted OpenAPI URLs`.

### Task 6: Phase acceptance

- [ ] Run all domain/application/persistence/storage/fetch tests with PostgreSQL 17.9 and MinIO containers.
- [ ] Capture packet/network evidence that fetch runner cannot reach the internet except through the gateway network.
- [ ] Scan logs and test reports for URLs, DNS answers, OIDC claims, object keys, and response bodies.
- [ ] Confirm the Web process has no URL HTTP client or egress credentials.

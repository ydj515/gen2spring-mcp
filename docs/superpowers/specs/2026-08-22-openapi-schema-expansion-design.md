# Bounded OpenAPI Schema Expansion Design

- Status: approved
- Date: 2026-08-22
- GitHub issue: `#12 Expand bounded OpenAPI schema support beyond nullable body descendants`
- Related specifications:
  - `docs/superpowers/specs/2026-08-14-openapi-31-guided-editor-design.md`
  - `docs/superpowers/specs/2026-08-17-openapi-nullable-array-constraints-design.md`

## 1. Purpose

Expand the canonical OpenAPI-to-MCP schema contract with carefully bounded nullable parameter and root-body
semantics, array upper and uniqueness bounds, composition, multi-type unions, and OpenAPI 3.1 `$ref` siblings.
The root OpenAPI 3.0 and 3.1 fixtures become executable reproductions of every supported behavior and every retained
unsupported boundary.

## 2. Goals

- Accept nullable optional query and header parameters without inventing a textual null wire value.
- Preserve absent versus explicit JSON null for nullable root request bodies.
- Carry `maxItems` and `uniqueItems` through analysis, Tool IR, MCP schemas, generated validation, Runtime Metadata,
  Managed Runtime validation, previews, and compatibility diff.
- Support bounded `allOf`, `oneOf`, `anyOf`, arbitrary OpenAPI 3.1 type unions, and semantic `$ref` siblings without
  silently dropping constraints.
- Keep Spring AI 1 and Spring AI 2 generated behavior equivalent.
- Keep unsupported endpoints visible with stable issue codes and reasons.

## 3. Non-goals

- Nullable path parameters or required nullable query/header parameters.
- Serializing a null query/header value as the literal text `null`.
- Recursive references, discriminator-driven polymorphic model generation, tuple arrays, or unbounded composition.
- Automatically generated Java sealed hierarchies for composed schemas.
- Relaxing existing schema, argument size, recursion, secret, provider error, retry, pagination, or response limits.
- A new editor information architecture.

## 4. Canonical Schema Model

`OpenApiDocument.SchemaType` gains a distinct composed/generic JSON form. `ApiSchema` retains existing fields and
adds:

- nullable `Integer maxItems`
- `boolean uniqueItems`
- optional bounded composition with kind `ONE_OF` or `ANY_OF` and immutable branches

`allOf` is not retained after normalization: compatible object branches are merged into one canonical object schema.
OpenAPI 3.1 multi-type arrays normalize to bounded `ANY_OF` branches, with a single null member represented by the
existing `nullable` flag. OpenAPI 3.0 `nullable: true` and OpenAPI 3.1 null unions continue to normalize identically.

The analyzer permits at most eight branches in one composition, sixteen schema levels, and sixty-four total
composition branches per operation. Existing source and schema-size bounds remain in force. Recursive references,
budget overflow, empty unions, and unsatisfiable intersections remain unsupported.

## 5. Nullable Parameter Semantics

Optional query and header parameters may have a nullable schema. Their MCP input schema advertises nullability, but
both an absent argument and an explicitly null argument omit the HTTP parameter. A null value is never converted to
a textual header or query value.

Path parameters remain required and non-null. Required nullable query/header declarations and every nullable path
declaration remain unsupported with fixed location-specific issue codes. CLI and both Web modes expose the endpoint
and the reason instead of silently excluding it from analysis output.

## 6. Nullable Root Body Semantics

A nullable root body is represented as one Tool input named `body` rather than flattening root object properties.
This preserves the root value and its presence as a single contract. Existing non-null object bodies retain their
property-flattened Tool inputs.

Wire behavior is exact:

- optional body absent: no body and no request content type;
- nullable body explicitly null: `Content-Type: application/json` with bytes `null`;
- required nullable body absent: reject before provider invocation;
- required nullable body explicitly null: send JSON null;
- required non-null body absent or null: reject as today.

The generated `ToolArgumentContext` remains the authoritative presence map around Spring AI callback conversion.
Managed Runtime uses the incoming MCP argument map directly and applies the same presence rules.

## 7. Array Bounds and Structural Uniqueness

`maxItems` is valid only for arrays, must be non-negative, must not be lower than `minItems`, and is supported for
Tool inputs up to 256. `uniqueItems` is valid only for arrays with an explicit supported `maxItems`, so its work is
bounded by the published Tool contract. Larger or unbounded unique arrays remain visible as unsupported rather than
being silently clamped. The JSON Schema keywords are preserved in input and output schemas; argument enforcement
occurs before provider invocation.

Uniqueness uses canonical JSON structural equality:

- object member order is ignored;
- array order remains significant;
- numeric values compare by mathematical value, so `1` and `1.0` are equal;
- null, boolean, and string values compare by exact JSON value;
- nested arrays and objects use the same recursive rules.

Validation remains bounded by the supported maximum of 256 unique-array items, existing string/member/depth limits,
and a canonical hash with collision-safe equality confirmation. Duplicate or oversized inputs produce a fixed
argument validation failure and zero provider requests. Arrays without `uniqueItems` retain their existing resource
boundary; the analyzer never rewrites an OpenAPI `maxItems` value.

## 8. Composition and Reference Merging

`allOf` supports object schemas whose types and constraints can be intersected without ambiguity. The merge engine:

- unions required-property names;
- recursively intersects duplicate property schemas;
- intersects enum values;
- chooses the tighter numeric, string, and array bounds;
- treats `uniqueItems: true` as the tighter array constraint;
- keeps compatible formats and rejects format or type conflicts;
- uses the adjacent schema's description/default annotations without weakening referenced constraints.

OpenAPI 3.1 `$ref` siblings use the same intersection engine. The 3.0 fixture expresses the equivalent contract with
`allOf`, because 3.0 does not define semantic `$ref` siblings.

`oneOf` preserves its branches and accepts a value only when exactly one branch matches. `anyOf` accepts a value when
one or more branches match. Their MCP JSON Schemas preserve the original keyword. A composed position is generated as
the profile-appropriate Jackson `JsonNode`, while ordinary non-composed fields retain existing typed Java models.
Raw composed values are validated, converted without lossy stringification, and serialized unchanged to the provider.

Recursive `$ref`, discriminator semantics, conflicting `allOf`, and any composition outside the branch/depth/node
budgets remain unsupported with stable reasons.

## 9. Shared Validation Boundary

A canonical schema-value validator in the application layer validates ordinary and composed metadata schemas against
bounded Java JSON values. Generator representative-call validation and Managed Runtime HTTP request construction use
this implementation. Runtime Metadata decoding rejects schema keywords or combinations outside the canonical model.

Generated Spring AI projects render a profile-specific equivalent validator because downloaded projects cannot
depend on hosted application services. Contract fixtures assert the generated validator and the shared validator
make identical decisions for valid, invalid, absent, null, duplicate, and multi-branch values.

The MCP SDK's schema validation is treated as an additional boundary, not the only source of correctness.

## 10. Root Swagger Reproduction Fixtures

Both `swagger-3.0.yml` and `swagger-3.1.yml` gain semantically paired operations covering:

- optional nullable query and header omission;
- required and optional nullable root request bodies;
- minimum, maximum, and structurally unique arrays;
- compatible and conflicting `allOf`;
- bounded `oneOf` and `anyOf`;
- an OpenAPI 3.1 `$ref` sibling and its OpenAPI 3.0 `allOf` equivalent;
- intentionally unsupported nullable path and composition-budget/conflict cases.

Fixture acceptance compares operation IDs, support decisions, issue codes, canonical Tool schemas, generated source
contracts, and representative provider wire requests. Supported-count assertions are updated from the actual paired
fixtures rather than weakening or removing unsupported examples.

## 11. Error and Security Contract

- Invalid schema declarations fail during analysis before source generation.
- Invalid Tool arguments fail before provider invocation in generated and Managed Runtime modes.
- Failure messages contain fixed schema locations and issue codes, never argument values.
- Secret masking, credential binding, tracing, retry, pagination, provider response limits, interruption, and fatal
  error behavior remain unchanged.
- Null headers are omitted before credential injection; a credential binding remains authoritative for its target.
- Composition and uniqueness validation never allocates proportional to an unbounded caller stream.

## 12. Complexity and Bounds

For schema-node count `S` and argument-node count `A`, ordinary and composed validation takes `O(S * A)` time in the
bounded worst case and `O(S + A)` memory. Structural uniqueness is expected `O(A)` with canonical hashing and uses
bounded deep equality to resolve hash collisions. Diff comparison remains linear in canonical schema size.

## 13. Acceptance Criteria

- The paired 3.0 and 3.1 fixtures produce identical decisions and canonical schemas for equivalent operations.
- Optional nullable query/header nulls are omitted and required/path nullable declarations remain visible as
  unsupported with exact reasons.
- Root-body absence and explicit JSON null produce distinct verified HTTP requests for both generated profiles and
  Managed Runtime.
- `maxItems` and structural `uniqueItems` reject invalid arrays with zero upstream requests.
- `allOf`, `oneOf`, `anyOf`, multi-type unions, and 3.1 `$ref` siblings preserve exact bounded schema semantics.
- Conflicts, recursive references, discriminator behavior, and resource-budget overflow fail closed.
- Runtime Metadata encode/decode and Catalog diff preserve every newly supported schema keyword deterministically.
- CLI inspect, local Web analysis, hosted Web analysis, Spring AI 1, Spring AI 2, and Managed Runtime agree.
- Root fixture regression tests cover all supported and intentionally unsupported operations.
- README, PRD, user guide, and the relevant architecture HTML state the completed and retained boundaries accurately.

# OpenAPI Nullable Inputs and Array Bounds Design

- Status: approved
- Date: 2026-08-17
- Related specifications:
  - `docs/superpowers/specs/2026-08-07-openapi-mcp-generator-p0-design.md`
  - `docs/superpowers/specs/2026-08-14-openapi-31-guided-editor-design.md`

## 1. Purpose

Extend the bounded OpenAPI-to-MCP contract so object request bodies can preserve explicit JSON null values and arrays can enforce `minItems`. Keep the OpenAPI 3.0 and 3.1 root fixtures semantically equivalent and remove misleading media-type and nested-schema diagnostics from their selectable endpoint set.

## 2. Problem

The paired root fixtures describe JSON success responses using `*/*`, nullable request properties using the version-appropriate syntax, and one request array with `minItems: 1`. The media declaration is ambiguous and belongs in the documents. Nullable inputs and `minItems`, however, are valid API semantics and must not be removed merely to make generation succeed.

The existing typed Tool method path collapses an omitted optional argument and an explicitly supplied null into the same Java null value. Relaxing analysis without changing execution would therefore publish a nullable Tool schema but silently omit explicit nulls from the provider request.

`SCHEMA_NESTED_UNSUPPORTED` is also emitted when an otherwise supported nested object contains a separately unsupported nullable or constrained property. Its current message incorrectly suggests that all nested schemas are unsupported.

## 3. Goals

- Normalize OpenAPI 3.0 `nullable: true` and OpenAPI 3.1 `type: [T, null]` to the same canonical nullable schema.
- Support nullable properties and array items below a non-null object request body.
- Preserve omitted versus explicitly null request-body properties through the MCP call path.
- Render nullable MCP input schemas with a bounded `anyOf` containing the non-null schema and `{ "type": "null" }`.
- Support non-negative `minItems` in analysis, Tool schemas, generated validation, previews, and runtime validation.
- Keep nullable path, query, and header parameters and nullable root request bodies unsupported.
- Keep `maxItems`, `uniqueItems`, arbitrary multi-type unions, and user-authored composition schemas unsupported.
- Keep Spring AI 1 and Spring AI 2 generated behavior equivalent.
- Make all 26 operations in the paired root fixtures supported with no media warning.

## 4. Canonical Schema Contract

`OpenApiDocument.ApiSchema` continues to own `nullable` and gains nullable `Integer minItems`.

- `nullable` describes whether the JSON value may be null.
- `requiredProperties` describes whether an object key must be present.
- These properties are independent. A required nullable property must be present and may contain null.
- `minItems` is valid only for array schemas and must be greater than or equal to zero.
- A nullable input schema is rendered as:

```json
{
  "anyOf": [
    { "type": "string" },
    { "type": "null" }
  ]
}
```

Descriptions remain attached to the containing property schema. Non-null constraints remain inside the first `anyOf` branch.

## 5. Analyzer Boundary

Parameter normalization keeps nullable disabled. Response normalization keeps its existing nullable support. Request-body normalization allows nullable descendants, then rejects the operation when the request-body root itself is nullable or is not the existing supported shape.

`minItems` is copied into canonical array schemas. Negative values remain unsupported. `maxItems` and `uniqueItems` continue to produce `SCHEMA_CONSTRAINT_UNSUPPORTED`.

`SCHEMA_NESTED_UNSUPPORTED` retains its issue code for compatibility, but its message becomes `A nested schema contains unsupported features`. The analyzer continues to expose the primary child issue alongside this contextual issue.

## 6. Tool Schema and Generated Model Contract

`ExpectedToolSchemaFactory` renders nullable schemas with `anyOf` and array bounds with `minItems`. Required property lists remain unchanged.

Generated Java models use reference types for nullable values. `@NotNull` is emitted only when a value is required and non-nullable. Arrays with `minItems` receive `@Size(min = n)`. Existing string length validation remains unchanged.

For direct typed Tool method calls, passing null to a nullable body input means explicit null. Passing null to an optional non-nullable input means omission.

Generation configuration parsers preserve JSON null values in representative `tools/call` arguments. `ExpectedToolCallFactory` then accepts null only when the final Tool input schema is nullable and enforces `minItems` before publishing or validating a generated project. This keeps syntax parsing schema-agnostic while preserving fail-closed semantic validation.

## 7. MCP Execution Contract

The MCP call handler uses `request.arguments()` as the authoritative presence map after SDK schema validation. It opens a generated, thread-confined `ToolArgumentContext` around the existing synchronous `MethodToolCallback` invocation. The context is always removed in `close()`, retains no secrets, and supports nested scopes by restoring the previous value.

Generated input records consult this context while creating the provider argument map. When the context is active they use the original raw value only for keys that were present, preserving nested maps, lists, and nulls. When no context is active, direct typed Tool calls retain their existing behavior except that a nullable null is treated as explicit null. This keeps the existing callback validation, result conversion, provider error mapping, and fatal error boundary intact.

The generated executor binds request-body properties as follows:

- key absent: do not add the property;
- key present with non-null value: bind as today;
- key present with null value: add the property with JSON null;
- null for path, query, or header: fail closed because analysis never permits those nullable schemas.

Nested maps and lists retain null entries through Jackson serialization.

## 8. Error and Security Contract

- Invalid nullable or array schemas remain fail-closed during analysis.
- Invalid MCP arguments are rejected before provider invocation.
- An array shorter than `minItems` produces no upstream request.
- Provider error mapping, secret masking, tracing, retry, pagination, and response normalization remain unchanged.
- Raw argument values are never included in generated diagnostics.

## 9. Acceptance Criteria

- The paired OpenAPI 3.0 and 3.1 documents normalize to identical decisions and Tool contracts.
- All 26 paired operations are `SUPPORTED`; inferred media warnings are zero.
- Missing optional nullable properties are omitted from the provider body.
- Explicitly null optional or required-nullable properties are serialized as JSON null.
- Empty `cancelOrderItems.lines` is rejected with zero upstream requests.
- A valid line list produces exactly one expected provider request.
- Spring AI 1 and Spring AI 2 generated projects compile, start, list the exact Tool schemas, and execute representative nullable/minItems calls.
- CLI inspect, local Web analysis, and hosted Web analysis expose the same counts and issues.

## 10. Complexity

Schema normalization, schema rendering, and request binding remain linear in the number of schema or argument nodes: time `O(N)` and space `O(N)`. Existing recursion and input-size bounds remain the resource boundary.

## 11. Non-goals

- Full JSON Schema composition support.
- Nullable URI or header parameters.
- Nullable root request bodies.
- `maxItems`, `uniqueItems`, tuple arrays, or arbitrary array keywords.
- Changes to response nullability or response normalization semantics.
- UI redesign beyond clearer issue text and updated support counts.

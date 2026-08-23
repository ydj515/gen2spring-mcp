package io.gen2spring.mcp.adapter.emitter.springai2;

final class ReactiveHttpClientSourceRenderer {
    String render() {
        return """
                    private Mono<ProviderAttempt> executeOnce(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            List<String> secretNames,
                            List<String> secretValues) {
                        return Mono.defer(() -> {
                            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUri(baseUrl).path(operation.path());
                            Map<String, Object> pathVariables = new LinkedHashMap<>();
                            HttpHeaders headers = new HttpHeaders();
                            RequestBodyValue requestBody = operation.objectRequestBody() && operation.requestBodyRequired()
                                    ? new RequestBodyValue(true, new LinkedHashMap<String, Object>())
                                    : RequestBodyValue.absent();

                            for (ParameterBinding binding : operation.parameterBindings()) {
                                if (!arguments.containsKey(binding.sourceName())) {
                                    continue;
                                }
                                Object value = arguments.get(binding.sourceName());
                                if (value == null) {
                                    if (binding.targetLocation() == ParameterLocation.QUERY
                                            || binding.targetLocation() == ParameterLocation.HEADER) {
                                        continue;
                                    }
                                    if (binding.targetLocation() == ParameterLocation.PATH) {
                                        throw new RequestSerializationException();
                                    }
                                }
                                Object bound = bind(uriBuilder, pathVariables, headers, binding.targetLocation(),
                                        binding.targetName(), value, requestBody.value(), operation.objectRequestBody());
                                requestBody = binding.targetLocation() == ParameterLocation.BODY
                                        ? new RequestBodyValue(true, bound)
                                        : new RequestBodyValue(requestBody.present(), bound);
                            }
                            for (SecretBinding binding : operation.secretBindings()) {
                                secretNames.add(binding.propertyName());
                                secretNames.add(binding.targetName());
                                String value = environment.getProperty(binding.propertyName());
                                if (value == null || value.isBlank()) {
                                    if (binding.required()) {
                                        throw new RequiredSecretException();
                                    }
                                    continue;
                                }
                                secretValues.add(value);
                                Object bound = bind(uriBuilder, pathVariables, headers, binding.targetLocation(),
                                        binding.targetName(), value, requestBody.value(), operation.objectRequestBody());
                                requestBody = binding.targetLocation() == ParameterLocation.BODY
                                        ? new RequestBodyValue(true, bound)
                                        : new RequestBodyValue(requestBody.present(), bound);
                            }

                            removePropagationHeaders(headers);
                            String traceparent = runtimeTelemetry.currentTraceparent();
                            if (traceparent != null) {
                                headers.set("traceparent", traceparent);
                            }
                            URI uri = uriBuilder.encode().buildAndExpand(pathVariables).toUri();
                            WebClient.RequestBodySpec request = webClient
                                    .method(HttpMethod.valueOf(operation.method()))
                                    .uri(uri);
                            request.headers(target -> {
                                target.addAll(headers);
                                target.setAccept(List.of(MediaType.APPLICATION_JSON));
                            });
                            WebClient.RequestHeadersSpec<?> exchange = request;
                            if (requestBody.present() && allowsBody(operation.method())) {
                                exchange = request.contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody.value());
                            }
                            return exchange.exchangeToMono(response -> {
                                int status = response.statusCode().value();
                                String contentType = response.headers().asHttpHeaders()
                                        .getFirst(HttpHeaders.CONTENT_TYPE);
                                return response.bodyToMono(byte[].class)
                                        .defaultIfEmpty(new byte[0])
                                        .onErrorMap(DataBufferLimitException.class,
                                                failure -> new ResponseTooLargeException(status))
                                        .map(body -> new ProviderAttempt(
                                                responseNormalizer.normalize(
                                                        operation,
                                                        status,
                                                        parseContentType(contentType),
                                                        body,
                                                        secretNames,
                                                        secretValues),
                                                status,
                                                body.length));
                            });
                        });
                    }

                    private Object bind(
                            UriComponentsBuilder uriBuilder,
                            Map<String, Object> pathVariables,
                            HttpHeaders headers,
                            ParameterLocation location,
                            String targetName,
                            Object value,
                            Object requestBody,
                            boolean objectRequestBody) {
                        return switch (location) {
                            case PATH -> {
                                pathVariables.put(targetName, value);
                                yield requestBody;
                            }
                            case QUERY -> {
                                addQueryValues(uriBuilder, targetName, value);
                                yield requestBody;
                            }
                            case HEADER -> {
                                addHeaderValues(headers, targetName, value);
                                yield requestBody;
                            }
                            case BODY -> {
                                if (!objectRequestBody) {
                                    yield value;
                                }
                                Map<String, Object> objectBody = new LinkedHashMap<>();
                                if (requestBody instanceof Map<?, ?> existing) {
                                    for (Map.Entry<?, ?> entry : existing.entrySet()) {
                                        if (entry.getKey() instanceof String property) {
                                            objectBody.put(property, entry.getValue());
                                        }
                                    }
                                }
                                objectBody.put(targetName, value);
                                yield objectBody;
                            }
                        };
                    }

                    private void addQueryValues(UriComponentsBuilder builder, String name, Object value) {
                        if (value instanceof Iterable<?> values) {
                            values.forEach(item -> builder.queryParam(name, item));
                        } else if (value.getClass().isArray()) {
                            for (int index = 0; index < Array.getLength(value); index++) {
                                builder.queryParam(name, Array.get(value, index));
                            }
                        } else {
                            builder.queryParam(name, value);
                        }
                    }

                    private void addHeaderValues(HttpHeaders headers, String name, Object value) {
                        if (value instanceof Iterable<?> values) {
                            values.forEach(item -> headers.add(name, String.valueOf(item)));
                        } else if (value.getClass().isArray()) {
                            for (int index = 0; index < Array.getLength(value); index++) {
                                headers.add(name, String.valueOf(Array.get(value, index)));
                            }
                        } else {
                            headers.add(name, String.valueOf(value));
                        }
                    }

                    private boolean allowsBody(String method) {
                        return "POST".equals(method) || "PUT".equals(method)
                                || "PATCH".equals(method) || "DELETE".equals(method);
                    }

                    private void removePropagationHeaders(HttpHeaders headers) {
                        List<String> namesToRemove = new ArrayList<>();
                        headers.forEach((name, ignored) -> {
                            String normalized = name.toLowerCase(Locale.ROOT);
                            if (Set.of("traceparent", "tracestate", "baggage", "b3").contains(normalized)
                                    || normalized.startsWith("x-b3-")) {
                                namesToRemove.add(name);
                            }
                        });
                        namesToRemove.forEach(headers::remove);
                    }
                """;
    }
}

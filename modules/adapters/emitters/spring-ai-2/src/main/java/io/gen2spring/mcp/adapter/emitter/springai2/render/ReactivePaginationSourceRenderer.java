package io.gen2spring.mcp.adapter.emitter.springai2.render;

final class ReactivePaginationSourceRenderer {
    String render() {
        return """
                    private Mono<OperationOutcome> executePaginated(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            List<String> secretNames,
                            List<String> secretValues,
                            long deadlineNanos) {
                        PaginationPolicy policy = operation.paginationPolicy();
                        Set<String> initialTokens = policy.initialValue() == null
                                ? Set.of() : Set.of(paginationWireValue(policy.initialValue()));
                        return executePage(
                                operation,
                                arguments,
                                secretNames,
                                secretValues,
                                deadlineNanos,
                                new ReactivePageState(null, initialTokens, 0, 0),
                                policy.initialValue());
                    }

                    private Mono<OperationOutcome> executePage(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            List<String> secretNames,
                            List<String> secretValues,
                            long deadlineNanos,
                            ReactivePageState state,
                            Object pageValue) {
                        return executeWithRetry(
                                        operation,
                                        arguments,
                                        secretNames,
                                        secretValues,
                                        pageValue,
                                        deadlineNanos,
                                        0)
                                .flatMap(attempt -> {
                                    if (!(attempt.outcome() instanceof NormalizedSuccess)
                                            || attempt.responseBody() == null) {
                                        return Mono.just(attempt.outcome());
                                    }
                                    PageStep step;
                                    try {
                                        step = appendPage(operation.paginationPolicy(), state, attempt.responseBody());
                                    } catch (PageResourceException failure) {
                                        return Mono.just(providerError(
                                                operation,
                                                ProviderErrorCategory.LOCAL_RESOURCE,
                                                null,
                                                secretNames,
                                                secretValues));
                                    } catch (PageProtocolException failure) {
                                        return Mono.just(providerError(
                                                operation,
                                                ProviderErrorCategory.UPSTREAM_PROTOCOL,
                                                null,
                                                secretNames,
                                                secretValues));
                                    }
                                    if (step.terminal()) {
                                        byte[] aggregate = pageBytes(step.state().aggregate());
                                        return Mono.just(responseNormalizer.normalize(
                                                operation,
                                                attempt.httpStatus(),
                                                parseContentType(attempt.contentType()),
                                                aggregate,
                                                secretNames,
                                                secretValues));
                                    }
                                    return Mono.defer(() -> executePage(
                                            operation,
                                            arguments,
                                            secretNames,
                                            secretValues,
                                            deadlineNanos,
                                            step.state(),
                                            step.nextValue()));
                                });
                    }

                    private PageStep appendPage(
                            PaginationPolicy policy,
                            ReactivePageState state,
                            byte[] body) {
                        JsonNode page;
                        try {
                            page = jsonMapper.readTree(body);
                        } catch (RuntimeException failure) {
                            throw new PageProtocolException();
                        }
                        JsonNode pageItems = page.at(policy.itemsPointer());
                        if (!pageItems.isArray()) {
                            throw new PageProtocolException();
                        }
                        JsonNode aggregate = state.aggregate() == null
                                ? page.deepCopy() : state.aggregate().deepCopy();
                        JsonNode selected = aggregate.at(policy.itemsPointer());
                        if (!(selected instanceof ArrayNode aggregateItems)) {
                            throw new PageProtocolException();
                        }
                        if (state.aggregate() != null) {
                            pageItems.forEach(item -> aggregateItems.add(item.deepCopy()));
                        }
                        int pageCount = state.pageCount() + 1;
                        int itemCount = aggregateItems.size();

                        JsonNode next = page.at(policy.nextValuePointer());
                        boolean terminal = next.isMissingNode() || next.isNull()
                                || next.isTextual() && next.stringValue().isEmpty();
                        String wireValue = null;
                        Set<String> seenTokens = state.seenTokens();
                        if (!terminal) {
                            wireValue = paginationWireValue(next);
                            if (seenTokens.contains(wireValue)) {
                                throw new PageProtocolException();
                            }
                            java.util.LinkedHashSet<String> nextTokens = new java.util.LinkedHashSet<>(seenTokens);
                            nextTokens.add(wireValue);
                            seenTokens = Set.copyOf(nextTokens);
                        }
                        if (!next.isMissingNode()
                                || !terminal
                                || !aggregate.at(policy.nextValuePointer()).isMissingNode()) {
                            setPageValue(
                                    aggregate,
                                    policy.nextValuePointer(),
                                    terminal ? jsonMapper.nullNode() : next.deepCopy());
                        }
                        if (itemCount > policy.maxItems()
                                || !terminal && (pageCount >= policy.maxPages()
                                || itemCount >= policy.maxItems())) {
                            throw new PageResourceException();
                        }
                        ReactivePageState nextState = new ReactivePageState(
                                aggregate, seenTokens, pageCount, itemCount);
                        pageBytes(nextState.aggregate());
                        return new PageStep(nextState, wireValue, terminal);
                    }

                    private byte[] pageBytes(JsonNode aggregate) {
                        try {
                            byte[] bytes = jsonMapper.writeValueAsBytes(aggregate);
                            if (bytes.length > responseMaxBytes) {
                                throw new PageResourceException();
                            }
                            return bytes;
                        } catch (PageResourceException failure) {
                            throw failure;
                        } catch (RuntimeException failure) {
                            throw new PageProtocolException();
                        }
                    }

                    private String paginationWireValue(Object value) {
                        if (value instanceof String text && !text.isEmpty()) {
                            return text;
                        }
                        if (value instanceof java.math.BigInteger integer) {
                            return integer.toString();
                        }
                        if (value instanceof JsonNode node) {
                            if (node.isTextual() && !node.stringValue().isEmpty()) {
                                return node.stringValue();
                            }
                            if (node.isIntegralNumber()) {
                                return node.bigIntegerValue().toString();
                            }
                        }
                        throw new PageProtocolException();
                    }

                    private void setPageValue(JsonNode root, String pointer, JsonNode value) {
                        List<String> tokens = pageTokens(pointer);
                        JsonNode current = root;
                        for (int index = 0; index < tokens.size() - 1; index++) {
                            current = pageChild(current, tokens.get(index));
                        }
                        String leaf = tokens.get(tokens.size() - 1);
                        if (current instanceof ObjectNode object) {
                            object.set(leaf, value);
                        } else if (current instanceof ArrayNode array) {
                            int index = pageArrayIndex(leaf);
                            if (index >= array.size()) {
                                throw new PageProtocolException();
                            }
                            array.set(index, value);
                        } else {
                            throw new PageProtocolException();
                        }
                    }

                    private JsonNode pageChild(JsonNode current, String token) {
                        JsonNode child;
                        if (current instanceof ObjectNode object) {
                            child = object.get(token);
                        } else if (current instanceof ArrayNode array) {
                            int index = pageArrayIndex(token);
                            child = index < array.size() ? array.get(index) : null;
                        } else {
                            child = null;
                        }
                        if (child == null || child.isMissingNode()) {
                            throw new PageProtocolException();
                        }
                        return child;
                    }

                    private List<String> pageTokens(String pointer) {
                        if (pointer == null || !pointer.startsWith("/") || pointer.length() < 2) {
                            throw new PageProtocolException();
                        }
                        List<String> result = new ArrayList<>();
                        for (String encoded : pointer.substring(1).split("/", -1)) {
                            result.add(encoded.replace("~1", "/").replace("~0", "~"));
                        }
                        return List.copyOf(result);
                    }

                    private int pageArrayIndex(String value) {
                        try {
                            if (value.isEmpty() || value.length() > 1 && value.startsWith("0")) {
                                throw new NumberFormatException();
                            }
                            return Integer.parseInt(value);
                        } catch (NumberFormatException failure) {
                            throw new PageProtocolException();
                        }
                    }

                    private record ReactivePageState(
                            JsonNode aggregate,
                            Set<String> seenTokens,
                            int pageCount,
                            int itemCount) {
                        private ReactivePageState {
                            seenTokens = Set.copyOf(seenTokens);
                        }
                    }

                    private record PageStep(
                            ReactivePageState state,
                            String nextValue,
                            boolean terminal) {}
                """;
    }
}

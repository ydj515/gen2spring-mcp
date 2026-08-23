package io.gen2spring.mcp.adapter.emitter.springai2;

final class ReactiveRetrySourceRenderer {
    String render() {
        return """
                    private Mono<ProviderAttempt> executeWithRetry(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            List<String> secretNames,
                            List<String> secretValues,
                            Object internalPageValue,
                            long deadlineNanos,
                            int retryCount) {
                        return Mono.defer(() -> {
                            long remainingNanos = deadlineNanos - System.nanoTime();
                            if (remainingNanos <= 0) {
                                return Mono.just(timeoutAttempt(
                                        operation, secretNames, secretValues));
                            }
                            return executeAttempt(
                                    operation,
                                    arguments,
                                    secretNames,
                                    secretValues,
                                    internalPageValue,
                                    remainingNanos)
                                    .flatMap(result -> {
                                        ProviderAttempt attempt = result.attempt();
                                        RetryPolicy policy = operation.retryPolicy();
                                        boolean retryable = policy != null
                                                && retryCount < policy.maxRetries()
                                                && idempotent(operation.method())
                                                && (policy.retryableStatus(attempt.httpStatus())
                                                || policy.networkErrors() && result.networkFailure());
                                        if (!retryable) {
                                            return Mono.just(attempt);
                                        }
                                        long delayMillis = retryDelayMillis(
                                                policy, attempt.retryAfter(), retryCount);
                                        long delayNanos = Duration.ofMillis(delayMillis).toNanos();
                                        if (deadlineNanos - System.nanoTime() <= delayNanos) {
                                            return Mono.just(timeoutAttempt(
                                                    operation, secretNames, secretValues));
                                        }
                                        return Mono.delay(Duration.ofMillis(delayMillis))
                                                .then(Mono.defer(() -> executeWithRetry(
                                                        operation,
                                                        arguments,
                                                        secretNames,
                                                        secretValues,
                                                        internalPageValue,
                                                        deadlineNanos,
                                                        retryCount + 1)));
                                    });
                        });
                    }

                    private Mono<AttemptResult> executeAttempt(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            List<String> secretNames,
                            List<String> secretValues,
                            Object internalPageValue,
                            long remainingNanos) {
                        return Mono.defer(() -> {
                            RuntimeTelemetry.Call providerCall = runtimeTelemetry.startProviderCall(
                                    operation.operationId(), operation.method());
                            Mono<AttemptResult> result;
                            try (var ignored = providerCall.openScope()) {
                                result = executeOnce(
                                                operation,
                                                arguments,
                                                secretNames,
                                                secretValues,
                                                internalPageValue)
                                        .timeout(Duration.ofNanos(remainingNanos))
                                        .map(attempt -> new AttemptResult(
                                                completedAttempt(providerCall, attempt), false))
                                        .onErrorResume(failure -> Mono.fromSupplier(() -> {
                                            boolean networkFailure = retryableNetworkFailure(failure);
                                            OperationOutcome outcome = completeFailure(
                                                    providerCall,
                                                    operation,
                                                    failure,
                                                    secretNames,
                                                    secretValues);
                                            Integer status = outcome instanceof ProviderError providerError
                                                    ? providerError.httpStatus() : null;
                                            return new AttemptResult(
                                                    new ProviderAttempt(
                                                            outcome, status, null, null, null, null),
                                                    networkFailure);
                                        }));
                            }
                            return result.doOnCancel(() -> providerCall.complete(
                                    RuntimeTelemetry.Outcome.CANCELLED,
                                    RuntimeTelemetry.ErrorCategory.NONE,
                                    RuntimeTelemetry.HttpStatusClass.NONE));
                        });
                    }

                    private ProviderAttempt completedAttempt(
                            RuntimeTelemetry.Call providerCall,
                            ProviderAttempt attempt) {
                        completeProviderCall(providerCall, attempt);
                        return attempt;
                    }

                    private ProviderAttempt timeoutAttempt(
                            OperationDefinition operation,
                            List<String> secretNames,
                            List<String> secretValues) {
                        return new ProviderAttempt(
                                providerError(
                                        operation,
                                        ProviderErrorCategory.UPSTREAM_TIMEOUT,
                                        null,
                                        secretNames,
                                        secretValues),
                                null,
                                null,
                                null,
                                null,
                                null);
                    }

                    private boolean retryableNetworkFailure(Throwable failure) {
                        return findCause(failure, Error.class) == null
                                && findCause(failure, ResponseTooLargeException.class) == null
                                && (hasCause(failure, WebClientRequestException.class)
                                || hasCause(failure, IOException.class));
                    }

                    private boolean idempotent(String method) {
                        return "GET".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
                    }

                    private long retryDelayMillis(RetryPolicy policy, String retryAfter, int retryCount) {
                        long exponential = policy.initialBackoffMillis();
                        for (int index = 0; index < retryCount; index++) {
                            exponential = Math.min(policy.maxBackoffMillis(), exponential * 2);
                        }
                        long providerDelay = policy.respectRetryAfter() ? retryAfterMillis(retryAfter) : 0;
                        return Math.min(policy.maxBackoffMillis(), Math.max(exponential, providerDelay));
                    }

                    private long retryAfterMillis(String value) {
                        if (value == null || value.isEmpty()) {
                            return 0;
                        }
                        for (int index = 0; index < value.length(); index++) {
                            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                                return 0;
                            }
                        }
                        try {
                            return Math.multiplyExact(Long.parseLong(value), 1_000L);
                        } catch (ArithmeticException | NumberFormatException failure) {
                            return 0;
                        }
                    }

                    private record AttemptResult(ProviderAttempt attempt, boolean networkFailure) {}
                """;
    }
}

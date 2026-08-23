package io.gen2spring.mcp.adapter.emitter.springai2;

final class ReactiveResponseSourceRenderer {
    String render() {
        return """
                    private OperationOutcome mapFailure(
                            OperationDefinition operation,
                            Throwable failure,
                            List<String> secretNames,
                            List<String> secretValues) {
                        Error error = findCause(failure, Error.class);
                        if (error != null) {
                            throw error;
                        }
                        ResponseTooLargeException tooLarge = findCause(failure, ResponseTooLargeException.class);
                        if (tooLarge != null) {
                            if (tooLarge.status() < 200 || tooLarge.status() >= 300) {
                                return responseNormalizer.normalize(
                                        operation,
                                        tooLarge.status(),
                                        null,
                                        new byte[0],
                                        secretNames,
                                        secretValues);
                            }
                            return providerError(operation, ProviderErrorCategory.UPSTREAM_PROTOCOL,
                                    tooLarge.status(), secretNames, secretValues);
                        }
                        if (hasCause(failure, TimeoutException.class)
                                || hasCause(failure, ConnectTimeoutException.class)
                                || hasCause(failure, ReadTimeoutException.class)) {
                            return providerError(operation, ProviderErrorCategory.UPSTREAM_TIMEOUT,
                                    null, secretNames, secretValues);
                        }
                        if (hasCause(failure, WebClientRequestException.class)
                                || hasCause(failure, IOException.class)) {
                            return providerError(operation, ProviderErrorCategory.UPSTREAM_UNAVAILABLE,
                                    null, secretNames, secretValues);
                        }
                        if (hasCause(failure, RequiredSecretException.class)) {
                            return providerError(operation, ProviderErrorCategory.LOCAL_RESOURCE,
                                    null, secretNames, secretValues);
                        }
                        if (hasCause(failure, RequestSerializationException.class)) {
                            return providerError(operation, ProviderErrorCategory.UPSTREAM_PROTOCOL,
                                    null, secretNames, secretValues);
                        }
                        if (failure instanceof RuntimeException runtimeFailure) {
                            throw runtimeFailure;
                        }
                        throw new IllegalStateException("Generated upstream execution failed");
                    }

                    private OperationOutcome completeProviderCall(
                            RuntimeTelemetry.Call providerCall,
                            ProviderAttempt attempt) {
                        RuntimeTelemetry.HttpStatusClass statusClass = statusClass(attempt.httpStatus());
                        if (attempt.httpStatus() != null) {
                            providerCall.responseStatus(attempt.httpStatus());
                        }
                        if (attempt.responseBytes() != null) {
                            runtimeTelemetry.recordResponseBytes(statusClass, attempt.responseBytes());
                        }
                        if (attempt.outcome() instanceof NormalizedSuccess) {
                            providerCall.complete(RuntimeTelemetry.Outcome.SUCCESS,
                                    RuntimeTelemetry.ErrorCategory.NONE, statusClass);
                        } else {
                            ProviderError providerError = (ProviderError) attempt.outcome();
                            providerCall.complete(RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                    RuntimeTelemetry.ErrorCategory.valueOf(providerError.category().name()),
                                    statusClass);
                        }
                        return attempt.outcome();
                    }

                    private OperationOutcome completeFailure(
                            RuntimeTelemetry.Call providerCall,
                            OperationDefinition operation,
                            Throwable failure,
                            List<String> secretNames,
                            List<String> secretValues) {
                        try {
                            OperationOutcome outcome = mapFailure(
                                    operation, failure, secretNames, secretValues);
                            Integer status = outcome instanceof ProviderError providerError
                                    ? providerError.httpStatus() : null;
                            return completeProviderCall(providerCall, new ProviderAttempt(outcome, status, null));
                        } catch (Error fatal) {
                            providerCall.complete(RuntimeTelemetry.Outcome.FATAL,
                                    RuntimeTelemetry.ErrorCategory.FATAL,
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            throw fatal;
                        } catch (RuntimeException runtimeFailure) {
                            providerCall.complete(RuntimeTelemetry.Outcome.INTERNAL_ERROR,
                                    RuntimeTelemetry.ErrorCategory.UNEXPECTED_RUNTIME,
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            throw runtimeFailure;
                        }
                    }

                    private ProviderError providerError(
                            OperationDefinition operation,
                            ProviderErrorCategory category,
                            Integer status,
                            List<String> secretNames,
                            List<String> secretValues) {
                        return responseNormalizer.error(
                                operation, category, status, null, null, secretNames, secretValues);
                    }

                    private RuntimeTelemetry.HttpStatusClass statusClass(Integer status) {
                        if (status == null) {
                            return RuntimeTelemetry.HttpStatusClass.NONE;
                        }
                        if (status >= 200 && status < 300) {
                            return RuntimeTelemetry.HttpStatusClass.SUCCESS;
                        }
                        if (status >= 400 && status < 500) {
                            return RuntimeTelemetry.HttpStatusClass.CLIENT_ERROR;
                        }
                        if (status >= 500 && status < 600) {
                            return RuntimeTelemetry.HttpStatusClass.SERVER_ERROR;
                        }
                        return RuntimeTelemetry.HttpStatusClass.OTHER;
                    }

                    private MediaType parseContentType(String contentType) {
                        if (contentType == null) {
                            return null;
                        }
                        try {
                            return MediaType.parseMediaType(contentType);
                        } catch (InvalidMediaTypeException failure) {
                            return null;
                        }
                    }

                    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
                        return findCause(failure, type) != null;
                    }

                    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
                        Throwable current = failure;
                        while (current != null) {
                            if (type.isInstance(current)) {
                                return type.cast(current);
                            }
                            Throwable cause = current.getCause();
                            if (cause == current) {
                                break;
                            }
                            current = cause;
                        }
                        return null;
                    }
                """;
    }
}

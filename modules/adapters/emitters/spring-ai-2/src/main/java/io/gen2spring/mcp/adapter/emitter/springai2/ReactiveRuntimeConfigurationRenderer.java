package io.gen2spring.mcp.adapter.emitter.springai2;

final class ReactiveRuntimeConfigurationRenderer {
    String render() {
        return """
                        this.environment = environment;
                        this.runtimeTelemetry = java.util.Objects.requireNonNull(runtimeTelemetry);
                        this.jsonMapper = JsonMapper.builder()
                                .changeDefaultPropertyInclusion(inclusion ->
                                        inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                                .build();
                        this.responseNormalizer = new ResponseNormalizer(runtimeTelemetry);
                        this.baseUrl = requireHttpUri(environment.getRequiredProperty("provider.base-url"));
                        this.responseMaxBytes = requireResponseLimit(
                                environment.getRequiredProperty("provider.response-max-bytes", Integer.class));
                        long connectTimeoutMillis = requireTimeout(
                                environment.getRequiredProperty("provider.connect-timeout-millis", Long.class));
                        long readTimeoutMillis = requireTimeout(
                                environment.getRequiredProperty("provider.read-timeout-millis", Long.class));
                        this.totalTimeoutMillis = requireTimeout(
                                environment.getRequiredProperty("provider.total-timeout-millis", Long.class));
                        int maxConcurrentRequests = requireRequestCapacity(
                                environment.getRequiredProperty("provider.max-concurrent-requests", Integer.class));
                        int maxQueuedRequests = requireRequestCapacity(
                                environment.getRequiredProperty("provider.max-queued-requests", Integer.class));
                        this.connectionProvider = ConnectionProvider.builder("openapi-upstream")
                                .maxConnections(maxConcurrentRequests)
                                .pendingAcquireMaxCount(maxQueuedRequests)
                                .pendingAcquireTimeout(Duration.ofMillis(totalTimeoutMillis))
                                .build();
                        HttpClient httpClient = HttpClient.create(connectionProvider)
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeoutMillis))
                                .responseTimeout(Duration.ofMillis(readTimeoutMillis));
                        this.webClient = builder.clone()
                                .clientConnector(new ReactorClientHttpConnector(httpClient))
                                .codecs(configurer ->
                                        configurer.defaultCodecs().maxInMemorySize(responseMaxBytes))
                                .observationRegistry(ObservationRegistry.NOOP)
                                .build();
                """;
    }
}

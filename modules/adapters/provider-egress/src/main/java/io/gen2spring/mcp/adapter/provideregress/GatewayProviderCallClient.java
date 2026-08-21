package io.gen2spring.mcp.adapter.provideregress;

import io.gen2spring.mcp.application.managed.execution.ProviderCallClient;
import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import javax.net.ssl.SSLContext;

public final class GatewayProviderCallClient implements ProviderCallClient {
    private final URI endpoint;
    private final HttpClient http;
    private final ProviderEgressCodec codec = new ProviderEgressCodec();

    public GatewayProviderCallClient(URI endpoint, SSLContext sslContext) {
        this(requireEndpoint(endpoint, true), HttpClient.newBuilder()
                .sslContext(Objects.requireNonNull(sslContext, "sslContext"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    GatewayProviderCallClient(URI endpoint, HttpClient http) {
        this.endpoint = requireEndpoint(endpoint, false);
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public ProviderCallResponse execute(ProviderCallRequest request, Duration timeout) {
        try {
            byte[] wire = codec.encodeRequest(request, timeout);
            HttpRequest internal = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(wire))
                    .build();
            HttpResponse<InputStream> response = http.send(internal, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] responseWire = body.readNBytes(ProviderEgressCodec.MAX_WIRE_BYTES + 1);
                if (response.statusCode() != 200 || responseWire.length > ProviderEgressCodec.MAX_WIRE_BYTES
                        || response.headers().firstValue("Content-Type")
                                .map(value -> !value.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                                .orElse(true)) {
                    throw ProviderCallFailure.protocol();
                }
                return codec.decodeResponse(responseWire);
            }
        } catch (ProviderCallFailure failure) {
            throw failure;
        } catch (HttpTimeoutException failure) {
            throw ProviderCallFailure.timeout();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ProviderCallFailure.timeout();
        } catch (java.io.IOException failure) {
            throw ProviderCallFailure.unavailable();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw ProviderCallFailure.protocol();
        }
    }

    private static URI requireEndpoint(URI value, boolean requireTls) {
        if (value == null || !value.isAbsolute() || value.getHost() == null
                || requireTls && !"https".equals(value.getScheme())
                || !requireTls && !java.util.Set.of("http", "https").contains(value.getScheme())
                || !"/internal/provider-call".equals(value.getRawPath())
                || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException("Provider egress endpoint is invalid");
        }
        return value;
    }
}

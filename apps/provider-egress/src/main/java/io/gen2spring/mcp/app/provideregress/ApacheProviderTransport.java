package io.gen2spring.mcp.app.provideregress;

import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

final class ApacheProviderTransport implements ProviderTransport, AutoCloseable {
    private static final int MAX_BODY = 1_048_576;
    private final CloseableHttpClient client;

    ApacheProviderTransport(ValidatedProviderResolver resolver, Duration connectTimeout) {
        Objects.requireNonNull(resolver, "resolver");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || connectTimeout.compareTo(Duration.ofSeconds(5)) > 0) throw new ProviderEgressFailure();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                        .setHostnameVerifier(new DefaultHostnameVerifier()).buildClassic())
                .setMaxConnTotal(32)
                .setMaxConnPerRoute(8)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout)).build())
                .build();
        this.client = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableAutomaticRetries().disableContentCompression()
                .disableCookieManagement().build();
    }

    @Override
    public ProviderCallResponse execute(ProviderCallRequest unsafe, Duration timeout) {
        ProviderCallRequest request = ProviderRequestPolicy.requireAllowed(unsafe);
        var outbound = new HttpUriRequestBase(request.method().name(), request.uri());
        outbound.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(timeout))
                .setResponseTimeout(Timeout.of(timeout)).build());
        request.headers().forEach((name, values) -> values.forEach(value -> outbound.addHeader(name, value)));
        if (request.body().length > 0) {
            outbound.setEntity(new ByteArrayEntity(request.body(), ContentType.APPLICATION_OCTET_STREAM));
        }
        try (var response = client.execute(outbound)) {
            byte[] body = readBounded(response.getEntity() == null
                    ? java.io.InputStream.nullInputStream() : response.getEntity().getContent());
            return ProviderResponsePolicy.requireAllowed(response.getCode(), headers(response.getHeaders()), body);
        } catch (ProviderEgressFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ProviderEgressFailure();
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private byte[] readBounded(java.io.InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                total += read;
                if (total > MAX_BODY) throw new ProviderEgressFailure();
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private Map<String, List<String>> headers(Header[] source) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Header header : source) {
            values.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
        }
        return values;
    }
}

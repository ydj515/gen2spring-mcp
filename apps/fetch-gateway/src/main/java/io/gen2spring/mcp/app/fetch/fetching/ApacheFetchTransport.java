package io.gen2spring.mcp.app.fetch.fetching;

import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HttpClientHostnameVerifier;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

final class ApacheFetchTransport implements FetchTransport, AutoCloseable {
    private final CloseableHttpClient client;

    ApacheFetchTransport(ValidatedDnsResolver resolver, Duration connectTimeout) {
        this(createClient(resolver, connectTimeout));
    }

    ApacheFetchTransport(CloseableHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    private static CloseableHttpClient createClient(
            ValidatedDnsResolver resolver,
            Duration connectTimeout) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isZero()
                || connectTimeout.isNegative()
                || connectTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Fetch transport configuration is invalid");
        }
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                        .setHostnameVerifier(productionHostnameVerifier())
                        .buildClassic())
                .setMaxConnTotal(16)
                .setMaxConnPerRoute(4)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableContentCompression()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
    }

    static HttpClientHostnameVerifier productionHostnameVerifier() {
        return new DefaultHostnameVerifier();
    }

    @Override
    public Response execute(ImportTarget target, Duration timeout) {
        HttpGet request = new HttpGet(target.uri());
        request.setHeader("Accept", "application/json, application/yaml, text/yaml, */*;q=0.1");
        request.setHeader("Accept-Encoding", "gzip, identity");
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(timeout))
                .setResponseTimeout(Timeout.of(timeout))
                .build());
        CloseableHttpResponse response = null;
        try {
            @SuppressWarnings("deprecation")
            CloseableHttpResponse executed = client.execute(request);
            response = executed;
            HttpEntity entity = response.getEntity();
            InputStream content = entity == null ? InputStream.nullInputStream() : entity.getContent();
            return new Response(
                    response.getCode(),
                    headers(response.getHeaders()),
                    new ResponseBody(content, response));
        } catch (IOException | RuntimeException failure) {
            closeQuietly(response);
            throw new FetchFailure(failure instanceof IOException
                    && !contains(failure, ValidatedDnsResolver.DestinationRejected.class));
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private void closeQuietly(CloseableHttpResponse response) {
        if (response == null) {
            return;
        }
        try {
            response.close();
        } catch (IOException ignored) {
            // The fixed fetch failure remains authoritative.
        }
    }

    private boolean contains(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, List<String>> headers(Header[] headers) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Header header : headers) {
            values.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        values.forEach((name, entries) -> immutable.put(name, List.copyOf(entries)));
        return Map.copyOf(immutable);
    }

    private static final class ResponseBody extends FilterInputStream {
        private final CloseableHttpResponse response;

        private ResponseBody(InputStream body, CloseableHttpResponse response) {
            super(body);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                super.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                response.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}

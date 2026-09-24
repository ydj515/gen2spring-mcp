package io.gen2spring.mcp.adapter.urlfetch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.gen2spring.mcp.application.hosted.imports.port.out.UrlFetchClient;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UrlImportIntegrationTest {
    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    private Path temporaryDirectory;

    private HttpsServer server;
    private java.util.concurrent.ExecutorService executor;
    private Path clientKeyStore;
    private Path clientTrustStore;
    private URI endpoint;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<byte[]> response = new AtomicReference<>(validSource());
    private final AtomicReference<String> observedRequest = new AtomicReference<>();

    @BeforeEach
    void startMutualTlsServer() throws Exception {
        TlsFiles tls = createTlsFiles();
        clientKeyStore = tls.clientKeyStore();
        clientTrustStore = tls.clientTrustStore();
        SSLContext serverContext = sslContext(tls.serverKeyStore(), tls.serverTrustStore());
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(HttpsParameters parameters) {
                var sslParameters = getSSLContext().getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(true);
                parameters.setSSLParameters(sslParameters);
            }
        });
        server.createContext("/internal/fetch", exchange -> {
            try (exchange) {
                observedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = response.get();
                exchange.getResponseHeaders().set(
                        "Content-Type", status.get() == 200 ? "application/yaml" : "application/json");
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        endpoint = URI.create("https://localhost:" + server.getAddress().getPort() + "/internal/fetch");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void requiresMutualTlsAndReturnsOneBoundedPrivateResponse() throws Exception {
        HttpRequest unauthenticated = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertThrows(Exception.class, () -> HttpClient.newBuilder()
                .sslContext(sslContext(null, clientTrustStore))
                .build()
                .send(unauthenticated, HttpResponse.BodyHandlers.discarding()));

        GatewayUrlFetchClient client = client(1024);
        ImportTarget target = ImportTarget.parse(
                "https://private-marker.example.com/openapi.yaml?token=private");
        byte[] fetched;
        try (UrlFetchClient.FetchedSpecification result = client.fetch(target);
                InputStream body = result.body()) {
            fetched = body.readAllBytes();
            assertEquals(fetched.length, result.size());
            assertEquals("application/yaml", result.mediaType());
        }

        assertArrayEquals(validSource(), fetched);
        assertEquals(
                "{\"target\":\"https://private-marker.example.com/openapi.yaml?token=private\"}",
                observedRequest.get());
    }

    @Test
    void rejectsOversizeAndGatewayErrorsWithoutRetainingPrivateData() {
        response.set("x".repeat(65).getBytes(StandardCharsets.UTF_8));
        assertFailure(() -> client(64).fetch(ImportTarget.parse(
                "https://private-marker.example.com/openapi.yaml")));

        status.set(422);
        response.set("private response marker".getBytes(StandardCharsets.UTF_8));
        assertFailure(() -> client(1024).fetch(ImportTarget.parse(
                "https://private-marker.example.com/openapi.yaml")));
    }

    private GatewayUrlFetchClient client(int maxBytes) {
        return new GatewayUrlFetchClient(
                endpoint,
                clientKeyStore,
                PASSWORD,
                clientTrustStore,
                PASSWORD,
                maxBytes);
    }

    private void assertFailure(Runnable action) {
        UrlFetchFailure failure = assertThrows(UrlFetchFailure.class, action::run);
        assertEquals("URL import gateway request failed", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private-marker"));
        assertFalse(failure.toString().contains("private response"));
    }

    private TlsFiles createTlsFiles() throws Exception {
        Path serverKey = temporaryDirectory.resolve("server.p12");
        Path clientKey = temporaryDirectory.resolve("client.p12");
        Path serverCert = temporaryDirectory.resolve("server.cer");
        Path clientCert = temporaryDirectory.resolve("client.cer");
        Path serverTrust = temporaryDirectory.resolve("server-trust.p12");
        Path clientTrust = temporaryDirectory.resolve("client-trust.p12");

        generateKeyPair(serverKey, "server", "CN=localhost", "SAN=dns:localhost");
        generateKeyPair(clientKey, "client", "CN=import-runner", null);
        keytool("-exportcert", "-alias", "server", "-keystore", serverKey.toString(),
                "-storepass", String.valueOf(PASSWORD), "-file", serverCert.toString());
        keytool("-exportcert", "-alias", "client", "-keystore", clientKey.toString(),
                "-storepass", String.valueOf(PASSWORD), "-file", clientCert.toString());
        importCertificate(serverTrust, "client", clientCert);
        importCertificate(clientTrust, "server", serverCert);
        return new TlsFiles(serverKey, serverTrust, clientKey, clientTrust);
    }

    private void generateKeyPair(Path keyStore, String alias, String name, String extension) throws Exception {
        java.util.List<String> arguments = new java.util.ArrayList<>(java.util.List.of(
                "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", name, "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", String.valueOf(PASSWORD),
                "-keypass", String.valueOf(PASSWORD), "-noprompt"));
        if (extension != null) {
            arguments.add("-ext");
            arguments.add(extension);
        }
        keytool(arguments.toArray(String[]::new));
    }

    private void importCertificate(Path trustStore, String alias, Path certificate) throws Exception {
        keytool("-importcert", "-alias", alias, "-file", certificate.toString(),
                "-storetype", "PKCS12", "-keystore", trustStore.toString(),
                "-storepass", String.valueOf(PASSWORD), "-noprompt");
    }

    private void keytool(String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            throw new IllegalStateException("Test TLS setup failed");
        }
    }

    private SSLContext sslContext(Path keyStorePath, Path trustStorePath) throws Exception {
        KeyManagerFactory keys = null;
        if (keyStorePath != null) {
            KeyStore keyStore = loadKeyStore(keyStorePath);
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, PASSWORD);
        }
        KeyStore trustStore = loadKeyStore(trustStorePath);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private KeyStore loadKeyStore(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            keyStore.load(input, PASSWORD);
        }
        return keyStore;
    }

    private static byte[] validSource() {
        return "openapi: 3.0.3\ninfo: {}\npaths: {}\n".getBytes(StandardCharsets.UTF_8);
    }

    private record TlsFiles(
            Path serverKeyStore,
            Path serverTrustStore,
            Path clientKeyStore,
            Path clientTrustStore) {}
}

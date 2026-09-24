package io.gen2spring.mcp.app.importer.config;

import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.adapter.urlfetch.GatewayUrlFetchClient;
import io.gen2spring.mcp.app.importer.application.imports.ImportRunner;
import io.gen2spring.mcp.app.importer.application.imports.ImportRunnerFailure;
import io.gen2spring.mcp.app.importer.application.imports.port.out.ImportGatewayClient;
import io.gen2spring.mcp.app.importer.infrastructure.client.fetch.GatewayImportClientAdapter;
import io.gen2spring.mcp.app.importer.infrastructure.analysis.WorkspaceSpecificationAnalyzer;
import io.gen2spring.mcp.app.importer.presentation.job.ImportJobProtocol;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class ImportRunnerConfiguration {
    private static final Path TARGET = Path.of("/job/input/target.txt");
    private static final Path OUTPUT = Path.of("/job/output");
    private static final Path WORK = Path.of("/job/work/import");
    private static final Path KEY_STORE = Path.of("/run/secrets/fetch-client.p12");
    private static final Path KEY_PASSWORD = Path.of("/run/secrets/fetch-client-password");
    private static final Path TRUST_STORE = Path.of("/run/secrets/fetch-ca.p12");
    private static final Path TRUST_PASSWORD = Path.of("/run/secrets/fetch-ca-password");
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private ImportRunnerConfiguration() {}

    public static int run(Map<String, String> environment) {
        char[] keyPassword = null;
        char[] trustPassword = null;
        try {
            String endpoint = require(environment, "GEN2SPRING_FETCH_GATEWAY_ENDPOINT");
            keyPassword = secret(KEY_PASSWORD);
            trustPassword = secret(TRUST_PASSWORD);
            GatewayUrlFetchClient client = new GatewayUrlFetchClient(
                    URI.create(endpoint),
                    KEY_STORE,
                    keyPassword,
                    TRUST_STORE,
                    trustPassword,
                    MAX_BYTES);
            ImportGatewayClient gateway = new GatewayImportClientAdapter(client, MAX_BYTES);
            return new ImportJobProtocol(new ImportRunner(
                            new WorkspaceSpecificationAnalyzer(new SwaggerOpenApiAnalyzer(), WORK, MAX_BYTES),
                            gateway,
                            MAX_BYTES))
                    .run(TARGET, OUTPUT);
        } catch (Error fatal) {
            throw fatal;
        } catch (ImportRunnerFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ImportRunnerFailure();
        } finally {
            if (keyPassword != null) {
                Arrays.fill(keyPassword, '\0');
            }
            if (trustPassword != null) {
                Arrays.fill(trustPassword, '\0');
            }
        }
    }

    private static String require(Map<String, String> environment, String key) {
        if (environment == null) {
            throw new ImportRunnerFailure();
        }
        String value = environment.get(key);
        if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)) {
            throw new ImportRunnerFailure();
        }
        return value;
    }

    private static char[] secret(Path path) {
        byte[] bytes = null;
        try {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) < 1
                    || Files.size(path) > 1024) {
                throw new ImportRunnerFailure();
            }
            bytes = Files.readAllBytes(path);
            String value = new String(bytes, StandardCharsets.UTF_8).strip();
            if (value.isEmpty() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
                throw new ImportRunnerFailure();
            }
            return value.toCharArray();
        } catch (ImportRunnerFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ImportRunnerFailure();
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }
}

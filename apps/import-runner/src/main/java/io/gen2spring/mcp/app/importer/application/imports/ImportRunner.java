package io.gen2spring.mcp.app.importer.application.imports;

import io.gen2spring.mcp.app.importer.application.imports.port.out.ImportGatewayClient;
import io.gen2spring.mcp.app.importer.application.imports.port.out.ImportSpecificationAnalyzer;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.util.Arrays;
import java.util.Objects;

public final class ImportRunner {
    private final ImportSpecificationAnalyzer analyzer;
    private final ImportGatewayClient gateway;
    private final int maxSpecificationBytes;

    public ImportRunner(
            ImportSpecificationAnalyzer analyzer,
            ImportGatewayClient gateway,
            int maxSpecificationBytes) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (maxSpecificationBytes < 1 || maxSpecificationBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Import runner configuration is invalid");
        }
        this.maxSpecificationBytes = maxSpecificationBytes;
    }

    public ImportResult run(ImportTarget target) {
        byte[] fetchedBytes = null;
        try {
            ImportGatewayClient.Fetched fetched = gateway.fetch(Objects.requireNonNull(target, "target"));
            fetchedBytes = fetched.source();
            if (fetchedBytes.length < 1 || fetchedBytes.length > maxSpecificationBytes) {
                throw new IllegalArgumentException();
            }
            return new ImportResult(analyzer.analyze(fetchedBytes, fetched.mediaType()), fetched.mediaType());
        } catch (Exception failure) {
            throw new ImportRunnerFailure();
        } finally {
            if (fetchedBytes != null) {
                Arrays.fill(fetchedBytes, (byte) 0);
            }
        }
    }

    public record ImportResult(byte[] source, String mediaType) {
        public ImportResult {
            source = Arrays.copyOf(Objects.requireNonNull(source, "source"), source.length);
            Objects.requireNonNull(mediaType, "mediaType");
        }

        @Override
        public byte[] source() {
            return Arrays.copyOf(source, source.length);
        }
    }
}

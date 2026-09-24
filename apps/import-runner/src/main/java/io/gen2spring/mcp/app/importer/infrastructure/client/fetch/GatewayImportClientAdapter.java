package io.gen2spring.mcp.app.importer.infrastructure.client.fetch;

import io.gen2spring.mcp.adapter.urlfetch.GatewayUrlFetchClient;
import io.gen2spring.mcp.app.importer.application.imports.ImportRunnerFailure;
import io.gen2spring.mcp.app.importer.application.imports.port.out.ImportGatewayClient;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.util.Arrays;
import java.util.Objects;

public final class GatewayImportClientAdapter implements ImportGatewayClient {
    private final GatewayUrlFetchClient client;
    private final int maxBytes;

    public GatewayImportClientAdapter(GatewayUrlFetchClient client, int maxBytes) {
        this.client = Objects.requireNonNull(client, "client");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Import size limit is invalid");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public Fetched fetch(ImportTarget target) {
        try (var fetched = client.fetch(target); var body = fetched.body()) {
            byte[] source = body.readNBytes(maxBytes + 1);
            if (source.length < 1 || source.length > maxBytes || source.length != fetched.size()) {
                Arrays.fill(source, (byte) 0);
                throw new ImportRunnerFailure();
            }
            return new Fetched(source, fetched.mediaType());
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            throw new ImportRunnerFailure();
        }
    }
}

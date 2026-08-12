package io.gen2spring.mcp.app.cli.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.slf4j.spi.SLF4JServiceProvider;

class CliNopServiceProviderTest {
    @Test
    void loadsTheCliOwnedProviderThroughThePublicSlf4jSpi() {
        var providers = ServiceLoader.load(SLF4JServiceProvider.class).stream()
                .filter(provider -> provider.type().equals(CliNopServiceProvider.class))
                .toList();

        assertEquals(1, providers.size());
        SLF4JServiceProvider provider = providers.getFirst().get();
        provider.initialize();
        assertFalse(provider.getRequestedApiVersion().isBlank());
        assertSame(provider.getLoggerFactory(), provider.getLoggerFactory());
        assertSame(provider.getMarkerFactory(), provider.getMarkerFactory());
        assertSame(provider.getMDCAdapter(), provider.getMDCAdapter());
    }
}

package io.gen2spring.mcp.core;

import java.util.Locale;

final class PortablePathKey {
    private PortablePathKey() {}

    static String caseFolded(String portablePath) {
        return portablePath.toLowerCase(Locale.ROOT);
    }
}

package io.gen2spring.mcp.adapter.filesystem.generation;

import java.util.Locale;

final class PortablePathKey {
    private PortablePathKey() {}

    static String caseFolded(String portablePath) {
        return portablePath.toLowerCase(Locale.ROOT);
    }
}

package io.gen2spring.mcp.application.generation.port.out;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SourceSnapshot(String checksum, Map<String, EntryFingerprint> entries) {
    public SourceSnapshot {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public record EntryFingerprint(long rawBytes, String checksum) {}
}

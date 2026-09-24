package io.gen2spring.mcp.application.generation.port.out;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public record GeneratedToolSources(Map<String, byte[]> files) {
    private static final String INVALID_SOURCES_MESSAGE = "Generated Tool sources are invalid";

    public GeneratedToolSources {
        files = immutableFileBytes(files);
    }

    @Override
    public Map<String, byte[]> files() {
        return immutableFileBytes(files);
    }

    private static Map<String, byte[]> immutableFileBytes(Map<String, byte[]> source) {
        if (source == null) {
            throw new IllegalArgumentException(INVALID_SOURCES_MESSAGE);
        }
        Map<String, byte[]> sorted = new TreeMap<>();
        source.forEach((path, bytes) -> {
            if (invalidSourcePath(path) || bytes == null) {
                throw new IllegalArgumentException(INVALID_SOURCES_MESSAGE);
            }
            sorted.put(path, bytes.clone());
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static boolean invalidSourcePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")
                || path.length() >= 3 && isAsciiLetter(path.charAt(0))
                && path.charAt(1) == ':' && path.charAt(2) == '/'
                || path.indexOf('\\') >= 0
                || path.chars().anyMatch(Character::isISOControl)) {
            return true;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }
}

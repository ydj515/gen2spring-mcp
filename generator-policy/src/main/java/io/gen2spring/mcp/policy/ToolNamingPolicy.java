package io.gen2spring.mcp.policy;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Pattern;

public final class ToolNamingPolicy {
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern NON_IDENTIFIER_CHARACTERS = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern SEPARATORS = Pattern.compile("_+");
    private static final Pattern GENERIC_API_TOKEN = Pattern.compile("(?:^|_)api(?=_|$)");
    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public String generate(String... parts) {
        if (parts == null || parts.length == 0) {
            throw invalidName();
        }

        StringBuilder combined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!combined.isEmpty()) {
                combined.append('_');
            }
            combined.append(part);
        }

        String ascii = Normalizer.normalize(combined, Normalizer.Form.NFKD);
        ascii = COMBINING_MARKS.matcher(ascii).replaceAll("");
        ascii = ACRONYM_BOUNDARY.matcher(ascii).replaceAll("$1_$2");
        ascii = CAMEL_CASE_BOUNDARY.matcher(ascii).replaceAll("$1_$2");
        ascii = NON_IDENTIFIER_CHARACTERS.matcher(ascii).replaceAll("_");
        ascii = SEPARATORS.matcher(ascii).replaceAll("_");
        ascii = GENERIC_API_TOKEN.matcher(ascii).replaceAll("_");
        ascii = SEPARATORS.matcher(ascii).replaceAll("_");
        ascii = stripSeparators(ascii).toLowerCase(java.util.Locale.ROOT);

        if (!VALID_NAME.matcher(ascii).matches()) {
            throw invalidName();
        }
        return ascii;
    }

    public void requireUnique(String name, Set<String> names) {
        if (names == null || !names.add(name)) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "tool-policy", "Tool names must be unique");
        }
    }

    private String stripSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }

    private GeneratorException invalidName() {
        return GeneratorException.user(SOURCE_GENERATION_FAILED, "tool-policy",
                "Tool name must match [a-z][a-z0-9_]{0,63}");
    }
}

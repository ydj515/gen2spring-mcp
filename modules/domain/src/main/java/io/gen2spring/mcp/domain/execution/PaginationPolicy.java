package io.gen2spring.mcp.domain.execution;

import java.math.BigInteger;

public record PaginationPolicy(
        String requestParameter,
        Object initialValue,
        String itemsPointer,
        String nextValuePointer,
        int maxPages,
        int maxItems) {
    private static final String INVALID_MESSAGE = "Pagination policy is invalid";
    private static final int MAX_POINTER_LENGTH = 256;
    private static final int MAX_POINTER_TOKENS = 32;

    public PaginationPolicy {
        if (requestParameter == null || requestParameter.isBlank() || requestParameter.length() > 128
                || invalidInitialValue(initialValue)
                || !validPointer(itemsPointer) || !validPointer(nextValuePointer)
                || maxPages < 2 || maxPages > 20
                || maxItems < 1 || maxItems > 2_000) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }

    private static boolean invalidInitialValue(Object value) {
        return value != null
                && (!(value instanceof String string) || string.isEmpty() || string.length() > 2_048)
                && !(value instanceof BigInteger);
    }

    private static boolean validPointer(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_POINTER_LENGTH || !value.startsWith("/")) {
            return false;
        }
        String[] tokens = value.substring(1).split("/", -1);
        if (tokens.length > MAX_POINTER_TOKENS) {
            return false;
        }
        for (String token : tokens) {
            if (token.equals("-") || !validToken(token)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validToken(String token) {
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (Character.isISOControl(character)) {
                return false;
            }
            if (character == '~' && (++index == token.length()
                    || token.charAt(index) != '0' && token.charAt(index) != '1')) {
                return false;
            }
        }
        return true;
    }
}

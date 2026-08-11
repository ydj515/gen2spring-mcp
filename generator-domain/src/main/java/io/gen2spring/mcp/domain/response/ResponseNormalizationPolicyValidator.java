package io.gen2spring.mcp.domain.response;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class ResponseNormalizationPolicyValidator {
    private static final String SAFE_MESSAGE = "Response normalization policy is invalid";
    private static final int MAX_POINTER_LENGTH = 256;
    private static final int MAX_POINTER_TOKENS = 32;
    private static final int MAX_SUCCESS_VALUES = 16;
    private static final int MAX_STRING_LENGTH = 128;

    public ResponseNormalizationPolicy requireValid(ResponseNormalizationPolicy policy) {
        if (policy == null) {
            throw invalid();
        }
        List<Pointer> pointers = Stream.of(
                        pointer("data", policy.dataPointer(), false),
                        pointer("successCode", policy.successCodePointer(), true),
                        pointer("errorMessage", policy.errorMessagePointer(), true),
                        pointer("totalCount", policy.totalCountPointer(), true))
                .filter(Objects::nonNull)
                .toList();
        boolean hasCode = policy.successCodePointer() != null;
        if (hasCode != !policy.successValues().isEmpty()
                || policy.successValues().size() > MAX_SUCCESS_VALUES
                || policy.successValues().stream().anyMatch(value -> !validScalar(value))) {
            throw invalid();
        }
        rejectScalarAncestorCollisions(pointers);
        return policy;
    }

    private Pointer pointer(String name, String value, boolean scalar) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty() || value.length() > MAX_POINTER_LENGTH || !value.startsWith("/")) {
            throw invalid();
        }

        List<String> tokens = new ArrayList<>();
        String[] encodedTokens = value.substring(1).split("/", -1);
        if (encodedTokens.length > MAX_POINTER_TOKENS) {
            throw invalid();
        }
        for (String encodedToken : encodedTokens) {
            String token = decodeToken(encodedToken);
            if (token.equals("-") || containsControl(token)) {
                throw invalid();
            }
            tokens.add(token);
        }
        return new Pointer(name, List.copyOf(tokens), scalar);
    }

    private String decodeToken(String encodedToken) {
        StringBuilder decoded = new StringBuilder(encodedToken.length());
        for (int index = 0; index < encodedToken.length(); index++) {
            char character = encodedToken.charAt(index);
            if (character != '~') {
                decoded.append(character);
                continue;
            }
            if (++index == encodedToken.length()) {
                throw invalid();
            }
            char escape = encodedToken.charAt(index);
            if (escape == '0') {
                decoded.append('~');
            } else if (escape == '1') {
                decoded.append('/');
            } else {
                throw invalid();
            }
        }
        return decoded.toString();
    }

    private void rejectScalarAncestorCollisions(List<Pointer> pointers) {
        for (Pointer scalarPointer : pointers) {
            if (!scalarPointer.scalar()) {
                continue;
            }
            for (Pointer otherPointer : pointers) {
                if (scalarPointer == otherPointer) {
                    continue;
                }
                if (isAncestorOrSame(scalarPointer.tokens(), otherPointer.tokens())) {
                    throw invalid();
                }
            }
        }
    }

    private boolean isAncestorOrSame(List<String> ancestor, List<String> descendant) {
        if (ancestor.size() > descendant.size()) {
            return false;
        }
        for (int index = 0; index < ancestor.size(); index++) {
            if (!ancestor.get(index).equals(descendant.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean validScalar(Object value) {
        if (value instanceof String string) {
            return string.length() <= MAX_STRING_LENGTH && !containsControl(string);
        }
        return value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal;
    }

    private boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException(SAFE_MESSAGE);
    }

    private record Pointer(String name, List<String> tokens, boolean scalar) {}
}

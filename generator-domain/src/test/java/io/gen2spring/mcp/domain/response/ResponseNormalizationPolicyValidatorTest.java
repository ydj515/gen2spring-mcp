package io.gen2spring.mcp.domain.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ResponseNormalizationPolicyValidatorTest {
    @ParameterizedTest
    @MethodSource("invalidPolicies")
    void rejectsInvalidPoliciesWithoutEchoingConfiguredValues(ResponseNormalizationPolicy policy) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ResponseNormalizationPolicyValidator().requireValid(policy));

        assertEquals("Response normalization policy is invalid", failure.getMessage());
    }

    @Test
    void rejectsNullPoliciesWithTheFixedValidationMessage() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ResponseNormalizationPolicyValidator().requireValid(null));

        assertEquals("Response normalization policy is invalid", failure.getMessage());
    }

    static Stream<ResponseNormalizationPolicy> invalidPolicies() {
        return Stream.of(
                policy("", null, List.of(), null, null),
                policy("relative", null, List.of(), null, null),
                policy("/bad~2escape", null, List.of(), null, null),
                policy("/items/-", null, List.of(), null, null),
                policy("/" + "x".repeat(256), null, List.of(), null, null),
                policy("/" + String.join("/", Collections.nCopies(33, "x")), null, List.of(), null, null),
                policy(null, "/code", List.of(), null, null),
                policy(null, null, List.of("00"), null, null),
                policy(null, "/code", Collections.nCopies(17, "00"), null, null),
                policy(null, "/code", List.of("x".repeat(129)), null, null),
                policy("/meta", "/meta/code", List.of("00"), null, "/meta"));
    }

    @Test
    void acceptsEscapedObjectNamesArrayIndexesAndTypedValues() {
        var policy = policy("/items/0/a~1b", "/meta/code", List.of("00", 0, false),
                "/meta/message", "/meta/total");

        assertSame(policy, new ResponseNormalizationPolicyValidator().requireValid(policy));
    }

    @Test
    void acceptsLeadingZeroTokensAsPotentialObjectPropertyNames() {
        var policy = policy("/items/01", null, List.of(), null, null);

        assertSame(policy, new ResponseNormalizationPolicyValidator().requireValid(policy));
    }

    @Test
    void acceptsAbsentPointersAndTheExplicitEmptyPropertyToken() {
        var absent = policy(null, null, List.of(), null, null);
        var emptyProperty = policy("/", null, List.of(), null, null);

        assertSame(absent, new ResponseNormalizationPolicyValidator().requireValid(absent));
        assertSame(emptyProperty, new ResponseNormalizationPolicyValidator().requireValid(emptyProperty));
    }

    private static ResponseNormalizationPolicy policy(
            String dataPointer,
            String successCodePointer,
            List<?> successValues,
            String errorMessagePointer,
            String totalCountPointer) {
        return new ResponseNormalizationPolicy(
                dataPointer, successCodePointer, new ArrayList<>(successValues), errorMessagePointer, totalCountPointer);
    }
}

package io.gen2spring.mcp.domain.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaginationPolicyTest {
    @Test
    void preservesBoundedStringAndExactIntegerInitialValues() {
        assertEquals("first", policy("first").initialValue());
        assertEquals(new BigInteger("9223372036854775808"),
                policy(new BigInteger("9223372036854775808")).initialValue());
        assertEquals(null, policy(null).initialValue());
    }

    @Test
    void acceptsEscapedRfc6901PointersAndBounds() {
        PaginationPolicy policy = new PaginationPolicy(
                "cursor", "first", "/response~1body/items~0all", "/response/next", 20, 2_000);

        assertEquals("/response~1body/items~0all", policy.itemsPointer());
        assertEquals(20, policy.maxPages());
        assertEquals(2_000, policy.maxItems());
    }

    @Test
    void rejectsInvalidPoliciesWithOneFixedMessage() {
        List<PaginationPolicyArguments> invalid = List.of(
                new PaginationPolicyArguments("", "first", "/items", "/next", 2, 1),
                new PaginationPolicyArguments("cursor", "", "/items", "/next", 2, 1),
                new PaginationPolicyArguments("cursor", "x".repeat(2_049), "/items", "/next", 2, 1),
                new PaginationPolicyArguments("cursor", true, "/items", "/next", 2, 1),
                new PaginationPolicyArguments("cursor", java.math.BigDecimal.ONE, "/items", "/next", 2, 1),
                new PaginationPolicyArguments("cursor", "first", "items", "/next", 2, 1),
                new PaginationPolicyArguments("cursor", "first", "/items", "", 2, 1),
                new PaginationPolicyArguments("cursor", "first", "/items~2", "/next", 2, 1),
                new PaginationPolicyArguments("cursor", "first", "/items", "/next", 1, 1),
                new PaginationPolicyArguments("cursor", "first", "/items", "/next", 21, 1),
                new PaginationPolicyArguments("cursor", "first", "/items", "/next", 2, 0),
                new PaginationPolicyArguments("cursor", "first", "/items", "/next", 2, 2_001));

        for (PaginationPolicyArguments value : invalid) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, value::create);
            assertEquals("Pagination policy is invalid", failure.getMessage());
        }
    }

    private PaginationPolicy policy(Object initialValue) {
        return new PaginationPolicy("cursor", initialValue, "/items", "/next", 10, 1_000);
    }

    private record PaginationPolicyArguments(
            String requestParameter,
            Object initialValue,
            String itemsPointer,
            String nextValuePointer,
            int maxPages,
            int maxItems) {
        private PaginationPolicy create() {
            return new PaginationPolicy(
                    requestParameter, initialValue, itemsPointer, nextValuePointer, maxPages, maxItems);
        }
    }
}

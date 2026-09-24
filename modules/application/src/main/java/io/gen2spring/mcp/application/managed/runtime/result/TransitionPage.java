package io.gen2spring.mcp.application.managed.runtime.result;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record TransitionPage(
        List<RuntimeCatalogTransition> items,
        Optional<Long> nextBefore) {
    public TransitionPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextBefore = Objects.requireNonNull(nextBefore, "nextBefore");
        if (nextBefore.filter(value -> value < 1).isPresent()) {
            throw new IllegalArgumentException("Runtime Catalog transition page is invalid");
        }
        long previous = Long.MAX_VALUE;
        for (RuntimeCatalogTransition item : items) {
            if (item.sequence() >= previous) {
                throw new IllegalArgumentException("Runtime Catalog transition page is invalid");
            }
            previous = item.sequence();
        }
    }
}

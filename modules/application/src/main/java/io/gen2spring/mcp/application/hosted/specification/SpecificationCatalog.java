package io.gen2spring.mcp.application.hosted.specification;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;

@FunctionalInterface
public interface SpecificationCatalog {
    boolean belongsTo(AccountId owner, SpecificationId specificationId);
}

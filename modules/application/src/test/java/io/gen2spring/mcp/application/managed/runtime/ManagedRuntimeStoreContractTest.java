package io.gen2spring.mcp.application.managed.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedRuntimeStoreContractTest {
    @Test
    void requiresImplementationsToPersistCredentialBindings() throws Exception {
        var legacy = ManagedRuntimeStore.class.getMethod(
                "create", ManagedRuntimeInstance.class, RuntimeTokenDigest.class);
        var bindingAware = ManagedRuntimeStore.class.getMethod(
                "create", ManagedRuntimeInstance.class, RuntimeTokenDigest.class, Map.class);

        assertTrue(legacy.isDefault());
        assertFalse(bindingAware.isDefault());
        assertTrue(Modifier.isAbstract(bindingAware.getModifiers()));
    }
}

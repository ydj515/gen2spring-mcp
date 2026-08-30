package io.gen2spring.mcp.app.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.app.importer.job.ImportRunnerFailure;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ImportRunnerApplicationTest {
    @Test
    void rejectsMissingGatewayConfigurationWithOneFixedFailure() {
        ImportRunnerFailure failure = assertThrows(
                ImportRunnerFailure.class,
                () -> ImportRunnerApplication.run(Map.of()));
        assertEquals("Specification import runner failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(5, ImportRunnerApplication.execute(new String[0], Map.of()));
        assertEquals(5, ImportRunnerApplication.execute(new String[] {"private-marker"}, Map.of()));
    }
}

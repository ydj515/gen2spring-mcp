package io.gen2spring.mcp.application.toolmodel.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ToolNamingPolicyTest {
    private final ToolNamingPolicy policy = new ToolNamingPolicy();

    @Test
    void generatesAStableSnakeCaseToolName() {
        assertEquals("kma_weather_get_forecast",
                policy.generate("KMA", "weather-api", "getForecast"));
    }

    @Test
    void normalizesUnicodeAndCollapsesSeparators() {
        assertEquals("weather_get_forecast",
                policy.generate("Wéather", "---get__Forecast---"));
    }

    @Test
    void rejectsAnEmptyOrDuplicateFinalName() {
        assertThrows(GeneratorException.class, () -> policy.generate("---"));

        var names = new HashSet<String>();
        policy.requireUnique("weather_forecast", names);
        assertThrows(GeneratorException.class, () -> policy.requireUnique("weather_forecast", names));
    }
}

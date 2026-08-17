package com.example.weather.generated.model;

import com.example.weather.runtime.ToolArgumentContext;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GetForecastInput(
        @NotNull @DecimalMin("0") @DecimalMax("1000") Integer nx,
        @NotNull @DecimalMin("0") @DecimalMax("1000") Integer ny) {
    public Map<String, Object> toArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (ToolArgumentContext.active()) {
            if (ToolArgumentContext.contains("nx")) {
                arguments.put("nx", ToolArgumentContext.value("nx"));
            }
        } else {
            arguments.put("nx", nx);
        }
        if (ToolArgumentContext.active()) {
            if (ToolArgumentContext.contains("ny")) {
                arguments.put("ny", ToolArgumentContext.value("ny"));
            }
        } else {
            arguments.put("ny", ny);
        }
        return Collections.unmodifiableMap(arguments);
    }
}

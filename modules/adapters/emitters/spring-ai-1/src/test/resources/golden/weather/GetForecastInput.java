package com.example.weather.generated.model;

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
        arguments.put("nx", nx);
        arguments.put("ny", ny);
        return Collections.unmodifiableMap(arguments);
    }
}

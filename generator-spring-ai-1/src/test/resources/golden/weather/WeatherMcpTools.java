package com.example.weather.generated.tool;

import com.example.weather.generated.metadata.WeatherOperations;
import com.example.weather.generated.model.GetForecastInput;
import com.example.weather.runtime.OpenApiOperationExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
public class WeatherMcpTools {
    private final OpenApiOperationExecutor executor;

    public WeatherMcpTools(OpenApiOperationExecutor executor) {
        this.executor = executor;
    }

    public JsonNode getForecast(
            @NotNull @DecimalMin("0") @DecimalMax("1000") Integer nx,
            @NotNull @DecimalMin("0") @DecimalMax("1000") Integer ny) {
        var input = new GetForecastInput(nx, ny);
        return executor.execute(WeatherOperations.GET_FORECAST, input.toArguments());
    }
}

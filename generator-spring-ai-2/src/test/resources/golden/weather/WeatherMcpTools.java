package com.example.weather.generated.tool;

import com.example.weather.generated.metadata.WeatherOperations;
import com.example.weather.generated.model.GetForecastInput;
import com.example.weather.runtime.OpenApiOperationExecutor;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.JsonNode;

@Component
@Validated
public class WeatherMcpTools {
    private final OpenApiOperationExecutor executor;

    public WeatherMcpTools(OpenApiOperationExecutor executor) {
        this.executor = executor;
    }

    public JsonNode getForecast(
            @NotNull @DecimalMin("0") @DecimalMax("1000") @McpToolParam(description = "Grid x coordinate", required = true) Integer nx,
            @NotNull @DecimalMin("0") @DecimalMax("1000") @McpToolParam(description = "Grid y coordinate", required = true) Integer ny) {
        var input = new GetForecastInput(nx, ny);
        return executor.execute(WeatherOperations.GET_FORECAST, input.toArguments());
    }
}

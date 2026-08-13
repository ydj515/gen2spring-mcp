package io.gen2spring.mcp.app.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
final class ProfileController {
    private final PreviewHandler previews;

    ProfileController(PreviewHandler previews) {
        this.previews = Objects.requireNonNull(previews, "previews");
    }

    @GetMapping("/api/profiles")
    JsonNode profiles() {
        return previews.profiles();
    }
}

package io.gen2spring.mcp.app.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ProfileController {
    private final GeneratorRuntime generator;
    private final ObjectMapper json;
    private final GenerationPreviewPresenter presenter;

    ProfileController(GeneratorRuntime generator, ObjectMapper json) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.json = Objects.requireNonNull(json, "json");
        this.presenter = new GenerationPreviewPresenter(this.json);
    }

    @GetMapping("/api/profiles")
    JsonNode profiles() {
        var root = json.createObjectNode();
        var profiles = root.putArray("profiles");
        generator.profiles().profiles().forEach(profile -> profiles.add(presenter.profile(profile)));
        return root;
    }
}

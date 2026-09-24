package io.gen2spring.mcp.app.web.presentation.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.app.web.application.local.service.LocalProfileService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ProfileController {
    private final LocalProfileService profiles;
    private final ObjectMapper json;
    private final GenerationPreviewPresenter presenter;

    ProfileController(LocalProfileService profiles, ObjectMapper json) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.json = Objects.requireNonNull(json, "json");
        this.presenter = new GenerationPreviewPresenter(this.json);
    }

    @GetMapping("/api/profiles")
    JsonNode profiles() {
        var root = json.createObjectNode();
        var profiles = root.putArray("profiles");
        this.profiles.profiles().forEach(profile -> profiles.add(presenter.profile(profile)));
        var notices = root.putArray("compatibilityNotices");
        this.profiles.notices()
                .forEach(notice -> notices.add(presenter.compatibilityNotice(notice)));
        return root;
    }
}

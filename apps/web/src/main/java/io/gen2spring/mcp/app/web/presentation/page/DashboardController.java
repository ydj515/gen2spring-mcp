package io.gen2spring.mcp.app.web.presentation.page;

import io.gen2spring.mcp.app.web.application.hosted.exception.HostedResourceNotFound;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedResourceQueryService;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.job.JobId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
final class DashboardController {
    private final HostedAccountResolver accounts;
    private final HostedResourceQueryService queries;

    DashboardController(HostedAccountResolver accounts, HostedResourceQueryService queries) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @GetMapping("/")
    String dashboard(Authentication authentication, HttpServletRequest request, Model model) {
        var owner = accounts.resolve(authentication).accountId();
        csrf(request, model);
        var dashboard = queries.dashboard(owner);
        model.addAttribute("specifications", dashboard.specifications());
        model.addAttribute("jobs", dashboard.jobs());
        return "dashboard";
    }

    @GetMapping("/editor")
    String editor(HttpServletRequest request, Model model) {
        csrf(request, model);
        model.addAttribute("appMode", "hosted");
        return "editor";
    }

    @GetMapping("/jobs/{id}")
    String job(Authentication authentication, @PathVariable String id, HttpServletRequest request, Model model) {
        var owner = accounts.resolve(authentication).accountId();
        JobId jobId;
        try { jobId = new JobId(UUID.fromString(id)); }
        catch (RuntimeException failure) { throw new HostedResourceNotFound(); }
        csrf(request, model);
        var snapshot = queries.job(owner, jobId);
        model.addAttribute("job", snapshot.job());
        model.addAttribute("events", snapshot.events());
        model.addAttribute("artifacts", snapshot.artifacts());
        return "job-detail";
    }

    private void csrf(HttpServletRequest request, Model model) {
        Object value = request.getAttribute(CsrfToken.class.getName());
        if (!(value instanceof CsrfToken token)) throw new IllegalStateException("CSRF token is unavailable");
        model.addAttribute("csrfToken", token.getToken());
        model.addAttribute("csrfHeader", token.getHeaderName());
    }
}

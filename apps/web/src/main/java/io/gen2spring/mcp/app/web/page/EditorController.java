package io.gen2spring.mcp.app.web.page;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
final class EditorController {
    @GetMapping({"/", "/editor"})
    String editor(HttpServletRequest request, Model model) {
        Object value = request.getAttribute(CsrfToken.class.getName());
        if (!(value instanceof CsrfToken csrf)) {
            throw new IllegalStateException("CSRF token is unavailable");
        }
        model.addAttribute("csrfToken", csrf.getToken());
        model.addAttribute("csrfHeader", csrf.getHeaderName());
        model.addAttribute("appMode", "local");
        return "editor";
    }
}

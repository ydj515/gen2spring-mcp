package io.gen2spring.mcp.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
final class EditorController {
    @GetMapping("/")
    String editor(HttpServletRequest request, Model model) {
        Object value = request.getAttribute(CsrfToken.class.getName());
        if (!(value instanceof CsrfToken csrf)) {
            throw new IllegalStateException("CSRF token is unavailable");
        }
        model.addAttribute("csrfToken", csrf.getToken());
        model.addAttribute("csrfHeader", csrf.getHeaderName());
        return "editor";
    }
}

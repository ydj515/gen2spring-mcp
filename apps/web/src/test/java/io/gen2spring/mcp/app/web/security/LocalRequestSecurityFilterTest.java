package io.gen2spring.mcp.app.web.security;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.app.web.Gen2SpringWebApplication;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(classes = Gen2SpringWebApplication.class)
@AutoConfigureMockMvc
class LocalRequestSecurityFilterTest {
    private static final int PORT = 18443;
    private static final String HOST = "127.0.0.1:" + PORT;
    private static final String ORIGIN = "http://" + HOST;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FilterRegistrationBean<LocalRequestSecurityFilter> localRequestSecurityFilterRegistration;

    @Autowired
    SecurityFilterChain localSecurity;

    @Autowired
    LocalRequestSecurityFilter localRequests;

    @Test
    void registersLocalRequestsOnlyInTheSecurityChain() {
        assertFalse(localRequestSecurityFilterRegistration.isEnabled());
        assertSame(localRequests, localRequestSecurityFilterRegistration.getFilter());
        assertEquals(1, localSecurity.getFilters().stream().filter(filter -> filter == localRequests).count());
    }

    @Test
    void acceptsOnlyExactLoopbackHostAndChangingOrigin() throws Exception {
        LocalRequestSecurityFilter filter = filter();

        assertTrue(accepted(filter, request("GET", HOST, null)));
        assertTrue(accepted(filter, request("POST", HOST, ORIGIN)));
        assertFalse(accepted(filter, request("GET", "localhost:" + PORT, null)));
        assertFalse(accepted(filter, request("POST", HOST, null)));
        assertFalse(accepted(filter, request("POST", HOST, "http://127.0.0.1:1")));

        MockHttpServletRequest nonLoopback = request("GET", HOST, null);
        nonLoopback.setRemoteAddr("127.0.0.2");
        assertFalse(accepted(filter, nonLoopback));

        MockHttpServletRequest duplicateHost = request("GET", HOST, null);
        duplicateHost.addHeader("Host", HOST);
        assertFalse(accepted(filter, duplicateHost));
    }

    @Test
    void rejectsEveryForwardingHeaderWithoutLeakingItsValue() throws Exception {
        for (String header : List.of(
                "Forwarded",
                "X-Forwarded-For",
                "X-Forwarded-Host",
                "X-Forwarded-Port",
                "X-Forwarded-Proto",
                "X-Real-IP")) {
            MockHttpServletRequest request = request("GET", HOST, null);
            request.addHeader(header, "private-forwarded-marker");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();

            filter().doFilter(request, response, (ignoredRequest, ignoredResponse) -> called.set(true));

            assertFalse(called.get(), header);
            assertEquals(403, response.getStatus(), header);
            assertEquals("REQUEST_FORBIDDEN",
                    new ObjectMapper().readTree(response.getContentAsByteArray())
                            .path("error").path("code").textValue(), header);
            assertFalse(response.getContentAsString().contains("private-forwarded-marker"), header);
        }
    }

    @Test
    void mapsMissingCsrfToTheFixedJsonEnvelope() throws Exception {
        mockMvc.perform(post("/api/profiles").with(localRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REQUEST_FORBIDDEN"))
                .andExpect(jsonPath("$.error.stage").value("HTTP"))
                .andExpect(jsonPath("$.error.message").value("The request is not allowed"));

        mockMvc.perform(post("/api/profiles")
                        .with(localRequest())
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(not(containsString("Invalid CSRF"))));
    }

    private LocalRequestSecurityFilter filter() {
        return new LocalRequestSecurityFilter(
                new WebErrorMapper(),
                new WebErrorResponseWriter(new ObjectMapper()));
    }

    private boolean accepted(LocalRequestSecurityFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> called.set(true));
        if (!called.get()) {
            assertEquals(403, response.getStatus());
            assertEquals("application/json;charset=UTF-8", response.getContentType());
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
        return called.get();
    }

    private MockHttpServletRequest request(String method, String host, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/profiles");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("127.0.0.1");
        request.setLocalPort(PORT);
        request.addHeader("Host", host);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private RequestPostProcessor localRequest() {
        return request -> {
            request.setRemoteAddr("127.0.0.1");
            request.setLocalAddr("127.0.0.1");
            request.setLocalPort(PORT);
            request.addHeader("Host", HOST);
            request.addHeader("Origin", ORIGIN);
            return request;
        };
    }
}

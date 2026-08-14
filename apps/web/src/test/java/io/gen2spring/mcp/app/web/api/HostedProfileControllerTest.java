package io.gen2spring.mcp.app.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ProfileController.class, properties = "gen2spring.mode=hosted")
@AutoConfigureMockMvc(addFilters = false)
@Import({WebErrorMapper.class, WebErrorResponseWriter.class})
class HostedProfileControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean GeneratorRuntime generator;

    @Test
    void exposesCanonicalProfilesInHostedMode() throws Exception {
        org.mockito.Mockito.when(generator.profiles()).thenReturn(CompatibilityProfileRegistry.defaults());

        mvc.perform(get("/api/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles.length()").value(4))
                .andExpect(jsonPath("$.profiles[0].id")
                        .value("spring-ai-1.1-java17-mvc-streamable"));
    }
}

package io.gen2spring.mcp.app.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.profile.CompatibilityCatalog;
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
        when(generator.profiles()).thenReturn(CompatibilityProfileRegistry.defaults());
        when(generator.compatibilityCatalog()).thenReturn(CompatibilityCatalog.defaults());

        mvc.perform(get("/api/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles.length()").value(12))
                .andExpect(jsonPath("$.compatibilityNotices.length()").value(1))
                .andExpect(jsonPath("$.compatibilityNotices[0].code")
                        .value("SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED"))
                .andExpect(jsonPath("$.profiles[0].id")
                        .value("spring-ai-1.1-java17-maven-mvc-streamable"))
                .andExpect(jsonPath("$.profiles[0].buildTool.type").value("MAVEN"));
    }
}

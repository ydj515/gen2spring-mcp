package io.gen2spring.mcp.app.web.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
final class ReadyReporter implements ApplicationListener<ApplicationReadyEvent> {
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext context)) {
            return;
        }
        int port = context.getWebServer().getPort();
        System.out.println("{\"status\":\"READY\",\"url\":\"http://127.0.0.1:"
                + port + "/\"}");
    }
}

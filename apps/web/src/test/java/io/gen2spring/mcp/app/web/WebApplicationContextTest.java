package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.app.web.api.SpecificationStore;
import io.gen2spring.mcp.app.web.job.GenerationJobManager;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        classes = Gen2SpringWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=0.0.0.0")
class WebApplicationContextTest {
    @Autowired
    ApplicationContext context;

    @Autowired
    ServletWebServerApplicationContext serverContext;

    @Autowired
    GeneratorRuntime application;

    @Autowired
    SpecificationStore specifications;

    @Autowired
    GenerationJobManager jobs;

    @Autowired
    ServerProperties serverProperties;

    @Autowired
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> loopbackOnly;

    @LocalServerPort
    int port;

    @Test
    void startsWithCanonicalOwnedServicesOnAnEphemeralPort() throws Exception {
        assertSame(application, context.getBean(GeneratorRuntime.class));
        assertTrue(port > 0 && port <= 65535);
        assertEquals(Duration.ZERO,
                serverProperties.getServlet().getSession().getTimeout());
        assertEquals(-1, serverContext.getServletContext().getSessionTimeout());
        Path temporaryParent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        assertTrue(specifications.root().startsWith(temporaryParent));
        assertTrue(jobs.root().startsWith(temporaryParent));
    }

    @Test
    void forcesNumericIpv4LoopbackAfterPropertyBinding() throws Exception {
        TomcatWebServer runningServer = (TomcatWebServer) serverContext.getWebServer();
        AbstractProtocol<?> protocol = (AbstractProtocol<?>) runningServer.getTomcat()
                .getConnector().getProtocolHandler();
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});

        assertEquals(loopback, protocol.getAddress());

        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        loopbackOnly.customize(factory);

        assertEquals(loopback, factory.getAddress());
    }

    @Test
    void containsNoManualHttpServerOrCapabilityTokenTransport() throws Exception {
        Path mainJava = Path.of("src/main/java").toAbsolutePath();
        List<String> sources;
        try (var paths = Files.walk(mainJava)) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (java.io.IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .toList();
        }
        assertTrue(sources.stream().noneMatch(source -> source.contains("com.sun.net.httpserver")));
        assertTrue(sources.stream().noneMatch(source -> source.contains("X-Gen2Spring-Token")));
        assertTrue(sources.stream().noneMatch(source -> source.contains("generator-api-token")));
        for (String obsolete : List.of(
                "LocalWebServer.java", "JsonHttp.java", "RequestGuard.java", "StaticAssetHandler.java",
                "WebApplicationFactory.java", "WebArguments.java", "Main.java")) {
            assertTrue(Files.notExists(mainJava.resolve("io/gen2spring/mcp/web").resolve(obsolete)), obsolete);
        }
    }
}

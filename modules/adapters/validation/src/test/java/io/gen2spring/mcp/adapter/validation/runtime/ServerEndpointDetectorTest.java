package io.gen2spring.mcp.adapter.validation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ServerEndpointDetectorTest {
    private final ServerEndpointDetector tomcat = new TomcatServerEndpointDetector();
    private final ServerEndpointDetector netty = new NettyServerEndpointDetector();

    @Test
    void acceptsTabsInOrdinaryLogLinesWithoutRelaxingStartupLineMatching() {
        String trace = "java.lang.IllegalStateException: recovered\n\tat sample.Application.start(Application.java:10)\n";
        assertEquals(URI.create("http://127.0.0.1:49152/mcp"), tomcat.detect(trace
                + "Tomcat started on port 49152 (http) with context path '/'\n").orElseThrow());
        assertEquals(URI.create("http://127.0.0.1:49153/mcp"), netty.detect(trace
                + "Netty started on port 49153 (http)\n").orElseThrow());
        assertTrue(netty.detect("Netty started on port 49153 (http)\tforged\n").isEmpty());
        assertTrue(netty.detect("\u000bNetty started on port 49153 (http)\n").isEmpty());
    }

    @Test
    void detectsExactlyOneTomcatEndpoint() {
        assertEquals(
                URI.create("http://127.0.0.1:49152/mcp"),
                tomcat.detect("2026-08-23 INFO Tomcat started on port 49152 (http) with context path '/'\n")
                        .orElseThrow());
    }

    @Test
    void detectsExactlyOneNettyEndpoint() {
        assertEquals(
                URI.create("http://127.0.0.1:49153/mcp"),
                netty.detect("2026-08-23 INFO Netty started on port 49153 (http)\n").orElseThrow());
    }

    @Test
    void rejectsMissingAndStackMismatchedLines() {
        assertTrue(tomcat.detect("application started\n").isEmpty());
        assertTrue(tomcat.detect("Netty started on port 49153 (http)\n").isEmpty());
        assertTrue(netty.detect("Tomcat started on port 49152 (http) with context path '/'\n").isEmpty());
    }

    @Test
    void rejectsDuplicateStartupLines() {
        String tomcatLine = "Tomcat started on port 49152 (http) with context path '/'\n";
        String nettyLine = "Netty started on port 49153 (http)\n";

        assertTrue(tomcat.detect(tomcatLine + tomcatLine).isEmpty());
        assertTrue(netty.detect(nettyLine + nettyLine).isEmpty());
    }

    @Test
    void rejectsPortsOutsideTheTcpRange() {
        assertTrue(tomcat.detect("Tomcat started on port 0 (http) with context path '/'\n").isEmpty());
        assertTrue(tomcat.detect("Tomcat started on port 65536 (http) with context path '/'\n").isEmpty());
        assertTrue(netty.detect("Netty started on port 999999 (http)\n").isEmpty());
    }

    @Test
    void rejectsAnsiAndControlText() {
        assertTrue(tomcat.detect("\u001b[32mTomcat started on port 49152 (http) with context path '/'\u001b[0m\n")
                .isEmpty());
        assertTrue(netty.detect("Netty started on port 49153 (http)\u0000\n").isEmpty());
    }

    @Test
    void rejectsOutputBeyondTheBoundedProcessBuffer() {
        String padding = "x".repeat(ServerEndpointDetector.MAX_OUTPUT_BYTES);

        assertTrue(tomcat.detect(padding + "\nTomcat started on port 49152 (http) with context path '/'\n")
                .isEmpty());
        assertTrue(netty.detect(padding + "\nNetty started on port 49153 (http)\n").isEmpty());
    }

    @Test
    void selectsTheDetectorFromTheCanonicalWebStack() {
        var validator = new ApplicationRuntimeValidator(
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofMillis(10),
                new LoopbackPortAllocator());

        assertInstanceOf(TomcatServerEndpointDetector.class,
                validator.requireEndpointDetector(profile("MVC")));
        assertInstanceOf(NettyServerEndpointDetector.class,
                validator.requireEndpointDetector(profile("WEBFLUX")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.requireEndpointDetector(profile("UNKNOWN")));
    }

    private CompatibilityProfile profile(String webStack) {
        return new CompatibilityProfile(
                "profile-" + webStack,
                new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", webStack, "SYNC", "STREAMABLE_HTTP"),
                "generator-spring-ai-2",
                "spring-ai-2-v3",
                "0.3.0",
                "9.6.1",
                "eclipse-temurin:21-jre");
    }
}

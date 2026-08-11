package io.gen2spring.mcp.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.Headers;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class RequestGuardTest {
    @Test
    void requiresExactLoopbackHostTokenAndChangingOrigin() throws Exception {
        RequestGuard guard = new RequestGuard(18443, "fixed-test-token");
        Headers valid = headers(
                "Host", "127.0.0.1:18443",
                "X-Gen2Spring-Token", "fixed-test-token",
                "Origin", "http://127.0.0.1:18443");

        assertDoesNotThrow(() -> guard.require(
                valid, InetAddress.getByName("127.0.0.1"), "POST", true));
        assertEquals(403, assertThrows(RequestGuard.RequestRejectedException.class,
                () -> guard.require(headers(
                                "Host", "localhost:18443",
                                "X-Gen2Spring-Token", "fixed-test-token",
                                "Origin", "http://127.0.0.1:18443"),
                        InetAddress.getByName("127.0.0.1"), "POST", true)).status());
        assertEquals(403, assertThrows(RequestGuard.RequestRejectedException.class,
                () -> guard.require(headers(
                                "Host", "127.0.0.1:18443",
                                "X-Gen2Spring-Token", "wrong",
                                "Origin", "http://127.0.0.1:18443"),
                        InetAddress.getByName("127.0.0.1"), "POST", true)).status());
        assertEquals(403, assertThrows(RequestGuard.RequestRejectedException.class,
                () -> guard.require(valid, InetAddress.getByName("192.0.2.1"), "POST", true)).status());
    }

    @Test
    void permitsRootWithoutTokenButStillRequiresTheExactHost() throws Exception {
        RequestGuard guard = new RequestGuard(18443, "fixed-test-token");

        assertDoesNotThrow(() -> guard.require(
                headers("Host", "127.0.0.1:18443"),
                InetAddress.getByName("127.0.0.1"), "GET", false));
        assertThrows(RequestGuard.RequestRejectedException.class, () -> guard.require(
                headers("Host", "127.0.0.1"),
                InetAddress.getByName("127.0.0.1"), "GET", false));
    }

    private Headers headers(String... values) {
        Headers headers = new Headers();
        for (int index = 0; index < values.length; index += 2) {
            headers.add(values[index], values[index + 1]);
        }
        return headers;
    }
}

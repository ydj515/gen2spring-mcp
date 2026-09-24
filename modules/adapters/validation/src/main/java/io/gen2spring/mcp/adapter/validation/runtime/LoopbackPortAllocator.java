package io.gen2spring.mcp.adapter.validation.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;

public final class LoopbackPortAllocator {
    private static final InetAddress IPV4_LOOPBACK = ipv4Loopback();

    public Reservation reserve() throws IOException {
        ServerSocket socket = new ServerSocket(0, 1, IPV4_LOOPBACK);
        socket.setReuseAddress(false);
        return new Reservation(socket);
    }

    public URI mcpUri(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Loopback port is out of range");
        }
        URI uri = URI.create("http://127.0.0.1:" + port + "/mcp");
        requireLoopback(uri);
        return uri;
    }

    public static void requireLoopback(URI uri) {
        if (uri == null
                || !"http".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getPort() < 1
                || !"/mcp".equals(uri.getPath())
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint must be an HTTP loopback URI");
        }
        String host = uri.getHost();
        if (!("127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host))) {
            throw new IllegalArgumentException("MCP endpoint host must be a numeric loopback address");
        }
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalArgumentException("MCP endpoint host is not loopback");
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("MCP endpoint host is invalid", exception);
        }
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    public static final class Reservation implements AutoCloseable {
        private final ServerSocket socket;
        private boolean released;

        private Reservation(ServerSocket socket) {
            this.socket = socket;
        }

        public int port() {
            return socket.getLocalPort();
        }

        public void releaseForLaunch() throws IOException {
            close();
        }

        @Override
        public void close() throws IOException {
            if (!released) {
                released = true;
                socket.close();
            }
        }
    }
}

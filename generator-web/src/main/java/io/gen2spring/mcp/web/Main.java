package io.gen2spring.mcp.web;

import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {}

    public static void main(String[] arguments) {
        LocalWebServer server = null;
        try {
            WebArguments parsed = WebArguments.parse(arguments);
            server = WebApplicationFactory.create(parsed.port());
            CountDownLatch stop = new CountDownLatch(1);
            LocalWebServer ownedServer = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                ownedServer.close();
                stop.countDown();
            }, "gen2spring-web-shutdown"));
            server.start();
            System.out.println("{\"status\":\"READY\",\"url\":\"" + server.uri() + "\"}");
            stop.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (server != null) {
                server.close();
            }
        } catch (RuntimeException exception) {
            if (server != null) {
                server.close();
            }
            System.err.println("The local Web application could not be started");
            System.exit(2);
        }
    }
}

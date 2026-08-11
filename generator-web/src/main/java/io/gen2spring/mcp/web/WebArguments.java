package io.gen2spring.mcp.web;

record WebArguments(int port) {
    static WebArguments parse(String[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return new WebArguments(0);
        }
        if (arguments.length != 2 || !"--port".equals(arguments[0])) {
            throw invalid();
        }
        try {
            int port = Integer.parseInt(arguments[1]);
            if (port < 0 || port > 65535) {
                throw invalid();
            }
            return new WebArguments(port);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Web arguments are invalid");
    }
}

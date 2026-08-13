package io.gen2spring.mcp.adapter.container;

final class DockerCommandFixture {
    private DockerCommandFixture() {}

    public static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "success" -> {
                System.out.println("standard");
                System.err.println("error");
            }
            case "oversized" -> System.out.print("x".repeat(65_537));
            case "sleep" -> Thread.sleep(10_000);
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
}

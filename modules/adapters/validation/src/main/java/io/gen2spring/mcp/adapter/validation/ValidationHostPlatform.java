package io.gen2spring.mcp.adapter.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

sealed interface ValidationHostPlatform permits PosixValidationHost, WindowsValidationHost {
    String SAFE_FAILURE = "Validation host platform is unsupported";

    static ValidationHostPlatform current() {
        return forHost(System.getProperty("os.name"), System.getenv());
    }

    static ValidationHostPlatform forHost(String osName, Map<String, String> environment) {
        if (osName == null || environment == null) {
            throw failure();
        }
        String normalized = osName.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("linux") || normalized.startsWith("mac os x")
                || normalized.startsWith("darwin") || normalized.startsWith("freebsd")
                || normalized.startsWith("openbsd") || normalized.startsWith("sunos")
                || normalized.startsWith("aix")) {
            return PosixValidationHost.INSTANCE;
        }
        if (normalized.startsWith("windows")) {
            String systemRoot = environment.get("SystemRoot");
            try {
                return new WindowsValidationHost(systemRoot == null ? null : Path.of(systemRoot));
            } catch (RuntimeException failure) {
                throw failure();
            }
        }
        throw failure();
    }

    String wrapperFileName();

    default String wrapperFileName(String buildTool) {
        return switch (buildTool) {
            case "GRADLE_KOTLIN" -> wrapperFileName();
            case "MAVEN" -> this instanceof WindowsValidationHost ? "mvnw.cmd" : "mvnw";
            default -> throw failure();
        };
    }

    Path javaExecutable(Path home);

    boolean requiresOwnerExecutable();

    List<String> buildCommand(Path wrapperSnapshot, Path javaHome);

    List<String> buildCommand(Path wrapperSnapshot, List<String> arguments);

    static void requireSafeWindowsArgument(String value) {
        if (value == null || value.isEmpty()) {
            throw failure();
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || character == '"' || character == '%'
                    || character == '!' || character == '^' || character == '&'
                    || character == '|' || character == '<' || character == '>') {
                throw failure();
            }
        }
    }

    static IllegalArgumentException failure() {
        return new IllegalArgumentException(SAFE_FAILURE);
    }
}

final class PosixValidationHost implements ValidationHostPlatform {
    static final PosixValidationHost INSTANCE = new PosixValidationHost();

    private PosixValidationHost() {}

    @Override
    public String wrapperFileName() {
        return "gradlew";
    }

    @Override
    public Path javaExecutable(Path home) {
        return Objects.requireNonNull(home, "home").resolve("bin/java").normalize();
    }

    @Override
    public boolean requiresOwnerExecutable() {
        return true;
    }

    @Override
    public List<String> buildCommand(Path wrapperSnapshot, Path javaHome) {
        Objects.requireNonNull(wrapperSnapshot, "wrapperSnapshot");
        Objects.requireNonNull(javaHome, "javaHome");
        return List.of(
                wrapperSnapshot.toString(),
                "-Dorg.gradle.java.installations.auto-detect=false",
                "-Dorg.gradle.java.installations.auto-download=false",
                "-Dorg.gradle.java.installations.paths=" + javaHome,
                "classes", "test", "bootJar", "--no-daemon", "--non-interactive");
    }

    @Override
    public List<String> buildCommand(Path wrapperSnapshot, List<String> arguments) {
        Objects.requireNonNull(wrapperSnapshot, "wrapperSnapshot");
        List<String> command = new java.util.ArrayList<>();
        command.add(wrapperSnapshot.toString());
        command.addAll(List.copyOf(arguments));
        return List.copyOf(command);
    }
}

final class WindowsValidationHost implements ValidationHostPlatform {
    private static final Pattern SNAPSHOT_NAME = Pattern.compile(
            "\\.(?:gradlew|mvnw)-validated-[0-9a-f-]+\\.(?:bat|cmd)");
    private static final Pattern MAVEN_SNAPSHOT_DIRECTORY = Pattern.compile(
            "\\.mvnw-validated-[0-9a-f-]+");

    private final Path command;
    private final StablePathIdentity commandIdentity;

    WindowsValidationHost(Path systemRoot) {
        try {
            if (systemRoot == null || !systemRoot.isAbsolute() || Files.isSymbolicLink(systemRoot)) {
                throw ValidationHostPlatform.failure();
            }
            Path root = systemRoot.normalize();
            if (!root.toRealPath().equals(root)) {
                throw ValidationHostPlatform.failure();
            }
            Path candidate = root.resolve("System32/cmd.exe").normalize();
            if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)
                    || !candidate.toRealPath().equals(candidate)) {
                throw ValidationHostPlatform.failure();
            }
            StablePathIdentity identity = StablePathIdentity.capture(candidate);
            if (!identity.regularFile()) {
                throw ValidationHostPlatform.failure();
            }
            this.command = candidate;
            this.commandIdentity = identity;
        } catch (IOException | RuntimeException failure) {
            throw ValidationHostPlatform.failure();
        }
    }

    @Override
    public String wrapperFileName() {
        return "gradlew.bat";
    }

    @Override
    public Path javaExecutable(Path home) {
        return Objects.requireNonNull(home, "home").resolve("bin/java.exe").normalize();
    }

    @Override
    public boolean requiresOwnerExecutable() {
        return false;
    }

    @Override
    public List<String> buildCommand(Path wrapperSnapshot, Path javaHome) {
        requireStableCommand();
        if (wrapperSnapshot == null || javaHome == null || wrapperSnapshot.getFileName() == null) {
            throw ValidationHostPlatform.failure();
        }
        String snapshotName = snapshotCommandPath(wrapperSnapshot);
        String home = javaHome.toString();
        ValidationHostPlatform.requireSafeWindowsArgument(home);
        String commandLine = "call " + snapshotName
                + " -Dorg.gradle.java.installations.auto-detect=false"
                + " -Dorg.gradle.java.installations.auto-download=false"
                + " -Dorg.gradle.java.installations.paths=\"" + home + "\""
                + " classes test bootJar --no-daemon --non-interactive";
        return List.of(command.toString(), "/D", "/E:OFF", "/V:OFF", "/S", "/C", commandLine);
    }

    @Override
    public List<String> buildCommand(Path wrapperSnapshot, List<String> arguments) {
        requireStableCommand();
        if (wrapperSnapshot == null || wrapperSnapshot.getFileName() == null || arguments == null) {
            throw ValidationHostPlatform.failure();
        }
        String snapshotName = snapshotCommandPath(wrapperSnapshot);
        StringBuilder commandLine = new StringBuilder("call ").append(snapshotName);
        for (String argument : List.copyOf(arguments)) {
            ValidationHostPlatform.requireSafeWindowsArgument(argument);
            commandLine.append(' ');
            if (argument.indexOf(' ') >= 0) {
                commandLine.append('"').append(argument).append('"');
            } else {
                commandLine.append(argument);
            }
        }
        return List.of(command.toString(), "/D", "/E:OFF", "/V:OFF", "/S", "/C", commandLine.toString());
    }

    private static String snapshotCommandPath(Path wrapperSnapshot) {
        String snapshotName = wrapperSnapshot.getFileName().toString();
        ValidationHostPlatform.requireSafeWindowsArgument(snapshotName);
        if (SNAPSHOT_NAME.matcher(snapshotName).matches()) {
            return snapshotName;
        }
        Path parent = wrapperSnapshot.getParent();
        if (!"mvnw.cmd".equals(snapshotName) || parent == null || parent.getFileName() == null) {
            throw ValidationHostPlatform.failure();
        }
        String directoryName = parent.getFileName().toString();
        ValidationHostPlatform.requireSafeWindowsArgument(directoryName);
        if (!MAVEN_SNAPSHOT_DIRECTORY.matcher(directoryName).matches()) {
            throw ValidationHostPlatform.failure();
        }
        return directoryName + "\\" + snapshotName;
    }

    private void requireStableCommand() {
        try {
            if (Files.isSymbolicLink(command) || !command.toRealPath().equals(command)) {
                throw ValidationHostPlatform.failure();
            }
            if (!commandIdentity.matches(command)) {
                throw ValidationHostPlatform.failure();
            }
        } catch (IOException | RuntimeException failure) {
            throw ValidationHostPlatform.failure();
        }
    }
}

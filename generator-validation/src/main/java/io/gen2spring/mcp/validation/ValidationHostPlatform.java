package io.gen2spring.mcp.validation;

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

    Path javaExecutable(Path home);

    boolean requiresOwnerExecutable();

    List<String> buildCommand(Path wrapperSnapshot, Path javaHome);

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
}

final class WindowsValidationHost implements ValidationHostPlatform {
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("\\.gradlew-validated-[0-9a-f-]+\\.bat");

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
        String snapshotName = wrapperSnapshot.getFileName().toString();
        String home = javaHome.toString();
        ValidationHostPlatform.requireSafeWindowsArgument(snapshotName);
        ValidationHostPlatform.requireSafeWindowsArgument(home);
        if (!SNAPSHOT_NAME.matcher(snapshotName).matches()) {
            throw ValidationHostPlatform.failure();
        }
        String commandLine = "call " + snapshotName
                + " -Dorg.gradle.java.installations.auto-detect=false"
                + " -Dorg.gradle.java.installations.auto-download=false"
                + " -Dorg.gradle.java.installations.paths=\"" + home + "\""
                + " classes test bootJar --no-daemon --non-interactive";
        return List.of(command.toString(), "/D", "/E:OFF", "/V:OFF", "/S", "/C", commandLine);
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

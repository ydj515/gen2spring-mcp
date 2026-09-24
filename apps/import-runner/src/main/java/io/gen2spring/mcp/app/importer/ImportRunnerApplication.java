package io.gen2spring.mcp.app.importer;

import io.gen2spring.mcp.app.importer.config.ImportRunnerConfiguration;
import java.util.Map;

public final class ImportRunnerApplication {
    private ImportRunnerApplication() {}

    public static void main(String[] args) {
        int exit = execute(args, System.getenv());
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int execute(String[] args, Map<String, String> environment) {
        if (args == null || args.length != 0) {
            return 5;
        }
        try {
            return run(environment);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            return 5;
        }
    }

    static int run(Map<String, String> environment) {
        return ImportRunnerConfiguration.run(environment);
    }
}

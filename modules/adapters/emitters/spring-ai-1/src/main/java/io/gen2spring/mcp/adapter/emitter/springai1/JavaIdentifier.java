package io.gen2spring.mcp.adapter.emitter.springai1;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;

import io.gen2spring.mcp.domain.error.GeneratorException;
import javax.lang.model.SourceVersion;
import java.util.regex.Pattern;

public final class JavaIdentifier {
    private static final Pattern DSL_SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private JavaIdentifier() {}

    public static String requirePackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            throw invalid("A Java package name is required");
        }
        for (String segment : packageName.split("\\.", -1)) {
            requireIdentifier(segment);
        }
        return packageName;
    }

    public static String requireIdentifier(String identifier) {
        if (identifier == null || !DSL_SAFE_IDENTIFIER.matcher(identifier).matches()
                || SourceVersion.isKeyword(identifier)) {
            throw invalid("Generated Java identifiers must be valid non-keyword identifiers");
        }
        return identifier;
    }

    private static GeneratorException invalid(String message) {
        return GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render", message);
    }
}

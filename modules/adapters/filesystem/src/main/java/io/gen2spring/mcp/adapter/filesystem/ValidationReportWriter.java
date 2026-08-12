package io.gen2spring.mcp.adapter.filesystem;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.port.outbound.ValidationReportStore;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.validation.ObservedTool;
import io.gen2spring.mcp.application.validation.ValidationReport;
import io.gen2spring.mcp.application.validation.ValidationStageResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class ValidationReportWriter implements ValidationReportStore {
    public static final String REPORT_FILE = "VALIDATION_REPORT.json";
    public static final int MAX_SUMMARY_CHARACTERS = 2_048;
    static final int MAX_SCAN_CHARACTERS = 65_536;
    private static final String STAGE = "REPORT";
    private static final Set<String> BUILT_IN_SECRET_NAMES = Set.of(
            "authorization",
            "apiKey",
            "api_key",
            "x-api-key",
            "serviceKey",
            "clientSecret",
            "accessToken",
            "cookie");

    private final ObjectMapper objectMapper;

    public ValidationReportWriter() {
        this(new ObjectMapper());
    }

    public ValidationReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Path write(Path projectRoot, ValidationReport report) {
        return write(projectRoot, report, List.of());
    }

    public Path write(Path projectRoot, ValidationReport report, Collection<String> configuredSecretNames) {
        if (report == null || report.status() == null || report.stages() == null || report.tools() == null) {
            throw failure("Validation report input is incomplete", null);
        }
        List<String> sensitiveNames = sensitiveNames(configuredSecretNames);
        Path target = outputPath(projectRoot);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("status", report.status().name());
        writeStages(json.putArray("stages"), report.stages(), sensitiveNames);
        writeTools(json.putArray("tools"), report.tools(), sensitiveNames);
        writeJson(target, json);
        return target;
    }

    public Path replaceAfterPipelineFailure(
            Path projectRoot,
            ValidationReport report,
            Collection<String> configuredSecretNames) {
        if (report == null || report.status() == null || report.stages() == null || report.tools() == null) {
            throw failure("Validation report input is incomplete", null);
        }
        List<String> sensitiveNames = sensitiveNames(configuredSecretNames);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("status", report.status().name());
        writeStages(json.putArray("stages"), report.stages(), sensitiveNames);
        writeTools(json.putArray("tools"), report.tools(), sensitiveNames);
        Path target = outputPath(projectRoot);
        replaceJson(target, json);
        return target;
    }

    private void writeStages(
            ArrayNode destination,
            List<ValidationStageResult> stages,
            List<String> sensitiveNames) {
        for (ValidationStageResult stage : stages) {
            if (stage == null || stage.stage() == null || stage.status() == null) {
                throw failure("Validation stage result is incomplete", null);
            }
            ObjectNode item = destination.addObject();
            item.put("stage", stage.stage());
            item.put("status", stage.status().name());
            item.put("durationMillis", Math.max(0, stage.durationMillis()));
            item.put("warningCount", Math.max(0, stage.warningCount()));
            item.put("errorCount", Math.max(0, stage.errorCount()));
            item.put("summary", sanitize(stage.summary(), sensitiveNames));
        }
    }

    private void writeTools(ArrayNode destination, List<ObservedTool> tools, List<String> sensitiveNames) {
        tools.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ObservedTool::name, Comparator.nullsFirst(String::compareTo)))
                .forEach(tool -> {
                    ObjectNode item = destination.addObject();
                    item.put("name", tool.name());
                    item.put("description", sanitize(tool.description(), sensitiveNames));
                    item.put("inputSchemaPresent", tool.inputSchemaPresent());
                });
    }

    private List<String> sensitiveNames(Collection<String> configuredSecretNames) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(BUILT_IN_SECRET_NAMES);
        if (configuredSecretNames != null) {
            configuredSecretNames.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .forEach(names::add);
        }
        return List.copyOf(names);
    }

    private String sanitize(String summary, List<String> sensitiveNames) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        String bounded = summary.substring(0, Math.min(summary.length(), MAX_SCAN_CHARACTERS));
        Set<String> lowercaseNames = new HashSet<>();
        sensitiveNames.forEach(name -> lowercaseNames.add(name.toLowerCase(Locale.ROOT)));
        String redacted = redactAssignments(bounded, lowercaseNames);
        redacted = redactBearerTokens(redacted);
        if (redacted.length() <= MAX_SUMMARY_CHARACTERS) {
            return redacted;
        }
        return redacted.substring(0, MAX_SUMMARY_CHARACTERS);
    }

    private String redactAssignments(String value, Set<String> sensitiveNames) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_SUMMARY_CHARACTERS));
        int index = 0;
        while (index < value.length()) {
            ParsedKey key;
            char current = value.charAt(index);
            if (current == '"') {
                key = parseQuotedKey(value, index);
                if (key == null) {
                    result.append(value, index, value.length());
                    break;
                }
            } else if ((index == 0 || !isKeyCharacter(value.charAt(index - 1)))
                    && isKeyCharacter(current)) {
                int end = index + 1;
                while (end < value.length() && isKeyCharacter(value.charAt(end))) {
                    end++;
                }
                key = new ParsedKey(value.substring(index, end), end);
            } else {
                result.append(current);
                index++;
                continue;
            }

            Assignment assignment = assignmentAfter(value, key, sensitiveNames);
            if (assignment == null) {
                result.append(value, index, key.end());
                index = key.end();
                continue;
            }
            result.append(value, index, assignment.valueStart());
            result.append("\"<redacted>\"");
            index = consumeValue(value, assignment.valueStart());
        }
        return result.toString();
    }

    private Assignment assignmentAfter(String value, ParsedKey key, Set<String> sensitiveNames) {
        if (!sensitiveNames.contains(key.decoded().toLowerCase(Locale.ROOT))) {
            return null;
        }
        int cursor = skipWhitespace(value, key.end());
        if (cursor >= value.length() || value.charAt(cursor) != ':' && value.charAt(cursor) != '=') {
            return null;
        }
        return new Assignment(skipWhitespace(value, cursor + 1));
    }

    private ParsedKey parseQuotedKey(String value, int start) {
        StringBuilder decoded = new StringBuilder();
        for (int index = start + 1; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"') {
                return new ParsedKey(decoded.toString(), index + 1);
            }
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++index >= value.length()) {
                return null;
            }
            char escaped = value.charAt(index);
            if (escaped == 'u' && index + 4 < value.length()) {
                try {
                    decoded.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                    index += 4;
                } catch (NumberFormatException exception) {
                    return null;
                }
            } else {
                decoded.append(switch (escaped) {
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> escaped;
                });
            }
        }
        return null;
    }

    private int consumeValue(String value, int start) {
        if (start >= value.length()) {
            return start;
        }
        char first = value.charAt(start);
        if (first == '"' || first == '\'') {
            for (int index = start + 1; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '\\') {
                    index++;
                } else if (current == first) {
                    return index + 1;
                }
            }
            return value.length();
        }
        int index = start;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\n' || current == '\r' || current == ',' || current == ';'
                    || current == '}' || current == ']') {
                break;
            }
            index++;
        }
        return index;
    }

    private String redactBearerTokens(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int copiedThrough = 0;
        int index = 0;
        while (index + 6 <= value.length()) {
            if ((index == 0 || !isKeyCharacter(value.charAt(index - 1)))
                    && value.regionMatches(true, index, "Bearer", 0, 6)
                    && index + 6 < value.length()
                    && Character.isWhitespace(value.charAt(index + 6))) {
                int tokenStart = skipWhitespace(value, index + 6);
                int tokenEnd = tokenStart;
                while (tokenEnd < value.length()) {
                    char current = value.charAt(tokenEnd);
                    if (Character.isWhitespace(current) || current == ',' || current == ';') {
                        break;
                    }
                    tokenEnd++;
                }
                result.append(value, copiedThrough, tokenStart).append("<redacted>");
                copiedThrough = tokenEnd;
                index = tokenEnd;
            } else {
                index++;
            }
        }
        result.append(value, copiedThrough, value.length());
        return result.toString();
    }

    private int skipWhitespace(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean isKeyCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '.';
    }

    private record ParsedKey(String decoded, int end) {}

    private record Assignment(int valueStart) {}

    private Path outputPath(Path projectRoot) {
        if (projectRoot == null || Files.isSymbolicLink(projectRoot)
                || !Files.isDirectory(projectRoot, NOFOLLOW_LINKS)) {
            throw failure("Validation report root must be a regular directory", null);
        }
        return projectRoot.toAbsolutePath().normalize().resolve(REPORT_FILE);
    }

    private void writeJson(Path target, ObjectNode json) {
        try {
            byte[] bytes = (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n").getBytes(UTF_8);
            Files.write(target, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw failure("Validation report could not be written", exception);
        }
    }

    private void replaceJson(Path target, ObjectNode json) {
        Path staging = null;
        try {
            byte[] bytes = (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n").getBytes(UTF_8);
            staging = Files.createTempFile(target.getParent(), ".validation-report-", ".tmp");
            Files.write(staging, bytes, WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW_LINKS);
            Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            staging = null;
        } catch (IOException exception) {
            throw failure("Validation report could not be replaced after pipeline failure", exception);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // Cleanup is restricted to the exact temporary report created above.
                }
            }
        }
    }

    private GeneratorException failure(String message, Throwable cause) {
        return cause == null
                ? GeneratorException.user(SOURCE_GENERATION_FAILED, STAGE, message)
                : GeneratorException.system(SOURCE_GENERATION_FAILED, STAGE, message, cause);
    }
}

package io.gen2spring.mcp.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ModuleDependencyDocumentationTest {
    private static final Set<String> SCOPES = Set.of(
            "api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly");
    private static final Pattern NODE = Pattern.compile("([a-zA-Z0-9_]+)\\[\"([^\"]+)\"\\]");
    private static final Pattern EDGE = Pattern.compile(
            "([a-zA-Z0-9_]+)\\s+(-->|-\\.->)(?:\\|([a-zA-Z]+)\\|)?\\s+([a-zA-Z0-9_]+)");
    private static final Pattern ROW = Pattern.compile("\\| \\[([^]]+)]\\(([^)]+)\\) \\| (.+) \\|");
    private static final Pattern TARGET = Pattern.compile("`([^`]+)` \\(([a-zA-Z]+)\\)");
    private static final Pattern COUNTS = Pattern.compile("production 모듈 \\*\\*(\\d+)개와 직접 project 의존 (\\d+)개\\*\\*");
    private static final Set<String> FIXTURE_MODULES = Set.of("apps/demo", "modules/domain", "modules/unused");
    private static final Set<Edge> FIXTURE_EDGES = Set.of(new Edge("apps/demo", "implementation", "modules/domain"));
    private static final String FIXTURE = """
            현재 Gradle production 모듈 **3개와 직접 project 의존 1개**를 나타낸다.
            ```mermaid
            flowchart LR
                app["apps/demo"]
                domain["modules/domain"]
                unused["modules/unused"]
                app --> domain
            ```
            | 출발 모듈 | 직접 의존 대상 |
            | --- | --- |
            | [apps/demo](../apps/demo/build.gradle.kts) | `modules/domain` (implementation) |
            | [modules/domain](../modules/domain/build.gradle.kts) | 없음 |
            | [modules/unused](../modules/unused/build.gradle.kts) | 없음 |
            """;

    @Test
    void diagramsAndTableMatchEvaluatedGradleDeclarations() throws Exception {
        String document = Files.readString(Path.of(System.getProperty("architecture.moduleDependencyDocument")));
        Set<String> modules = new LinkedHashSet<>();
        System.getProperty("architecture.moduleGraph").lines()
                .forEach(line -> modules.add(moduleName(line.substring(0, line.indexOf('=')))));
        Set<Edge> edges = new LinkedHashSet<>();
        System.getProperty("architecture.declaredModuleEdges").lines().filter(line -> !line.isBlank()).forEach(line -> {
            String[] parts = line.split("\\|", -1);
            assertEquals(3, parts.length, "Invalid Gradle edge: " + line);
            edges.add(new Edge(moduleName(parts[0]), parts[1], moduleName(parts[2])));
        });
        verify(document, modules, edges);
    }

    @Test
    void acceptsMatchingDiagramsAndTableIncludingAnIsolatedModule() {
        verify(FIXTURE, FIXTURE_MODULES, FIXTURE_EDGES);
    }

    @Test
    void rejectsMissingExtraDuplicateAndChangedDiagramEdges() {
        for (String replacement : new String[] {
                "", "domain --> app", "app --> domain\napp --> domain", "app -.->|api| domain",
                "app --> missing", "app -- domain"}) {
            assertThrows(AssertionError.class,
                    () -> verify(FIXTURE.replace("app --> domain", replacement), FIXTURE_MODULES, FIXTURE_EDGES));
        }
    }

    @Test
    void rejectsTableDriftInvalidLinksAndStaleCounts() {
        for (String document : new String[] {
                FIXTURE.replace("(implementation)", "(api)"),
                FIXTURE.replace("`modules/domain` (implementation)", "없음"),
                FIXTURE.replace("../apps/demo/build.gradle.kts", "../apps/other/build.gradle.kts"),
                FIXTURE.replace("의존 1개", "의존 2개"),
                FIXTURE + "| [modules/domain](../modules/domain/build.gradle.kts) | 없음 |\n"}) {
            assertThrows(AssertionError.class, () -> verify(document, FIXTURE_MODULES, FIXTURE_EDGES));
        }
    }

    @Test
    void rejectsUndocumentedModulesAndDependenciesFromGradle() {
        Set<String> modules = new LinkedHashSet<>(FIXTURE_MODULES);
        modules.add("apps/new");
        assertThrows(AssertionError.class, () -> verify(FIXTURE, modules, FIXTURE_EDGES));
        Set<Edge> edges = new LinkedHashSet<>(FIXTURE_EDGES);
        edges.add(new Edge("modules/domain", "runtimeOnly", "apps/demo"));
        assertThrows(AssertionError.class, () -> verify(FIXTURE, FIXTURE_MODULES, edges));
    }

    private static void verify(String document, Set<String> expectedModules, Set<Edge> expectedEdges) {
        Graph diagrams = diagrams(document);
        Graph table = table(document);
        assertEquals(expectedModules, diagrams.modules(), "Mermaid modules differ from Gradle");
        assertEquals(expectedEdges, diagrams.edges(), "Mermaid dependencies differ from Gradle");
        assertEquals(expectedModules, table.modules(), "Table modules differ from Gradle");
        assertEquals(expectedEdges, table.edges(), "Table dependencies differ from Gradle");
        var counts = COUNTS.matcher(document);
        assertTrue(counts.find(), "Missing module and dependency counts");
        assertEquals(expectedModules.size(), Integer.parseInt(counts.group(1)), "Stale module count");
        assertEquals(expectedEdges.size(), Integer.parseInt(counts.group(2)), "Stale dependency count");
    }

    private static Graph diagrams(String document) {
        Set<String> modules = new LinkedHashSet<>();
        Set<Edge> edges = new LinkedHashSet<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        boolean inDiagram = false;
        for (String raw : document.lines().toList()) {
            String line = raw.strip();
            if (line.equals("```mermaid")) {
                assertTrue(!inDiagram, "Nested Mermaid block");
                inDiagram = true;
                nodes.clear();
            } else if (inDiagram && line.equals("```")) {
                inDiagram = false;
            } else if (inDiagram && !line.isEmpty() && !line.equals("flowchart LR")
                    && !line.startsWith("accTitle:") && !line.startsWith("accDescr:")) {
                var node = NODE.matcher(line);
                var edge = EDGE.matcher(line);
                if (node.matches()) {
                    String name = moduleName(node.group(2));
                    assertTrue(nodes.putIfAbsent(node.group(1), name) == null, "Duplicate diagram node: " + line);
                    modules.add(name);
                } else {
                    assertTrue(edge.matches(), "Unsupported Mermaid declaration: " + line);
                    String scope = edge.group(3) == null ? "implementation" : edge.group(3);
                    assertTrue(!edge.group(2).equals("-.->") || scope.equals("api"), "Dotted edges must declare api");
                    assertTrue(nodes.containsKey(edge.group(1)) && nodes.containsKey(edge.group(4)),
                            "Declare both edge endpoints in the same diagram: " + line);
                    addEdge(edges, new Edge(nodes.get(edge.group(1)), scope, nodes.get(edge.group(4))));
                }
            }
        }
        assertTrue(!inDiagram, "Unclosed Mermaid block");
        return new Graph(modules, edges);
    }

    private static Graph table(String document) {
        Set<String> modules = new LinkedHashSet<>();
        Set<Edge> edges = new LinkedHashSet<>();
        for (String raw : document.lines().toList()) {
            String line = raw.strip();
            if (!line.startsWith("|") || line.equals("| 출발 모듈 | 직접 의존 대상 |")
                    || line.equals("| --- | --- |")) {
                continue;
            }
            var row = ROW.matcher(line);
            assertTrue(row.matches(), "Invalid dependency table row: " + line);
            String source = moduleName(row.group(1));
            assertTrue(modules.add(source), "Duplicate dependency table row: " + source);
            assertEquals("../" + source + "/build.gradle.kts", row.group(2), "Invalid module build link");
            if (!row.group(3).equals("없음")) {
                for (String item : row.group(3).split("<br>", -1)) {
                    var target = TARGET.matcher(item);
                    assertTrue(target.matches(), "Invalid dependency table target: " + item);
                    addEdge(edges, new Edge(source, target.group(2), moduleName(target.group(1))));
                }
            }
        }
        return new Graph(modules, edges);
    }

    private static void addEdge(Set<Edge> edges, Edge edge) {
        assertTrue(SCOPES.contains(edge.scope()), "Unsupported dependency scope: " + edge.scope());
        assertTrue(edges.add(edge), "Duplicate dependency: " + edge);
    }

    private static String moduleName(String value) {
        if (value.startsWith(":")) {
            return value.substring(1).replace(':', '/');
        }
        return value.startsWith("adapters/") ? "modules/" + value : value;
    }

    private record Edge(String source, String scope, String target) {}
    private record Graph(Set<String> modules, Set<Edge> edges) {}
}

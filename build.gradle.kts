import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    pmd
}

abstract class PmdExecutionLimit : BuildService<BuildServiceParameters.None>

private val JAVA_CODE = 0
private val JAVA_LINE_COMMENT = 1
private val JAVA_BLOCK_COMMENT = 2
private val JAVA_STRING = 3
private val JAVA_CHARACTER = 4
private val JAVA_TEXT_BLOCK = 5

private fun stripJavaLiteralsAndComments(source: String): String {
    val stripped = StringBuilder(source.length)
    var state = JAVA_CODE
    var index = 0
    while (index < source.length) {
        val current = source[index]
        when (state) {
            JAVA_CODE -> when {
                source.startsWith("//", index) -> {
                    stripped.append("  ")
                    index += 2
                    state = JAVA_LINE_COMMENT
                }
                source.startsWith("/*", index) -> {
                    stripped.append("  ")
                    index += 2
                    state = JAVA_BLOCK_COMMENT
                }
                source.startsWith("\"\"\"", index) -> {
                    stripped.append("   ")
                    index += 3
                    state = JAVA_TEXT_BLOCK
                }
                current == '\"' -> {
                    stripped.append(' ')
                    index++
                    state = JAVA_STRING
                }
                current == '\'' -> {
                    stripped.append(' ')
                    index++
                    state = JAVA_CHARACTER
                }
                else -> {
                    stripped.append(current)
                    index++
                }
            }
            JAVA_LINE_COMMENT -> {
                stripped.append(if (current == '\n') '\n' else ' ')
                index++
                if (current == '\n') state = JAVA_CODE
            }
            JAVA_BLOCK_COMMENT -> when {
                source.startsWith("*/", index) -> {
                    stripped.append("  ")
                    index += 2
                    state = JAVA_CODE
                }
                else -> {
                    stripped.append(if (current == '\n') '\n' else ' ')
                    index++
                }
            }
            JAVA_STRING, JAVA_CHARACTER -> when {
                current == '\\' && index + 1 < source.length -> {
                    stripped.append(' ')
                    stripped.append(if (source[index + 1] == '\n') '\n' else ' ')
                    index += 2
                }
                state == JAVA_STRING && current == '\"' -> {
                    stripped.append(' ')
                    index++
                    state = JAVA_CODE
                }
                state == JAVA_CHARACTER && current == '\'' -> {
                    stripped.append(' ')
                    index++
                    state = JAVA_CODE
                }
                else -> {
                    stripped.append(if (current == '\n') '\n' else ' ')
                    index++
                }
            }
            JAVA_TEXT_BLOCK -> when {
                source.startsWith("\"\"\"", index) -> {
                    stripped.append("   ")
                    index += 3
                    state = JAVA_CODE
                }
                else -> {
                    stripped.append(if (current == '\n') '\n' else ' ')
                    index++
                }
            }
        }
    }
    return stripped.toString()
}

private fun lineNumberAt(source: String, index: Int): Int =
    source.take(index).count { character -> character == '\n' } + 1

private data class JavaImportStyleViolation(
    val path: String,
    val line: Int,
    val message: String,
)

group = "io.gen2spring.mcp"
version = "0.1.0"

val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit)
    testImplementation(libs.pmd.java)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val productionClasses = files()
val productionProjects = subprojects.filter { it.buildFile.exists() }
val productionModuleGraph = providers.provider {
    productionProjects.sortedBy { it.path }.joinToString("\n") { module ->
        val targets = listOf("compileClasspath", "runtimeClasspath").flatMap { name ->
            module.configurations.getByName(name).allDependencies.withType(ProjectDependency::class.java)
                .map { it.path }
        }.distinct().sorted()
        "${module.path}=${targets.joinToString(",")}"
    }
}
val pmdVersion = libs.versions.pmd.get()
val pmdRules = layout.projectDirectory.file("config/pmd/ruleset.xml")

val pmdExecutionLimit = gradle.sharedServices.registerIfAbsent("pmdExecutionLimit", PmdExecutionLimit::class) {
    maxParallelUsages.set(2)
}

val verifyPmdRules = tasks.register<JavaExec>("verifyPmdRules") {
    group = "verification"
    description = "Fail on invalid or empty PMD configuration before analysis"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.gen2spring.mcp.architecture.PmdRuleSetVerifier")
    args(pmdRules.asFile.absolutePath)
    inputs.file(pmdRules)
}

val verifyJavaQuality = tasks.register("verifyJavaQuality") {
    group = "verification"
    description = "Run Java import policy and PMD across production and test source sets"
    dependsOn("verifyJavaImportStyle")
}

val architectureTest = tasks.register("architectureTest") {
    group = "verification"
    description = "Check compiled dependency directions and declared production module edges"
    dependsOn(tasks.test)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(verifyJavaQuality)
    dependsOn(productionClasses)
    classpath += productionClasses
    inputs.files(productionClasses)
    inputs.property("architecture.moduleGraph", productionModuleGraph)
    inputs.files(productionProjects.map { it.buildFile }, file("settings.gradle.kts"))
    systemProperty("quality.pmdRules", pmdRules.asFile.absolutePath)
    inputs.file(pmdRules)
    doFirst {
        systemProperty("architecture.productionDirectories",
            productionClasses.files.sortedBy { it.path }.joinToString("\n") { it.absolutePath })
        systemProperty("architecture.moduleGraph", productionModuleGraph.get())
    }
}

tasks.check { dependsOn(verifyJavaQuality, architectureTest) }

allprojects {
    if (this != rootProject && !buildFile.exists()) return@allprojects
    apply(plugin = "pmd")
    extensions.configure<PmdExtension> {
        toolVersion = pmdVersion
        ruleSets = emptyList()
        ruleSetFiles = files(pmdRules)
        isIgnoreFailures = false
        isConsoleOutput = true
    }
    val pmdTasks = tasks.withType<Pmd>()
    pmdTasks.configureEach {
        dependsOn(rootProject.tasks.named("verifyPmdRules"))
        usesService(pmdExecutionLimit)
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        doLast {
            val report = reports.xml.outputLocation.get().asFile.readText()
            check(!Regex("<(?:(?:[A-Za-z]+):)?(?:error|configerror)\\b").containsMatchIn(report)) {
                "PMD processing/configuration errors in ${reports.xml.outputLocation.get().asFile}"
            }
        }
    }
    rootProject.tasks.named("verifyJavaQuality") { dependsOn(pmdTasks) }
}

val javaImportStyleSources = fileTree(rootDir) {
    include("**/*.java")
    exclude("**/build/**", "**/.gradle/**", "**/.git/**")
}

val verifyJavaImportStyle = tasks.register("verifyJavaImportStyle") {
    group = "verification"
    description = "Reject fully qualified annotations and avoidable app FQCN usage"
    inputs.files(javaImportStyleSources)

    doLast {
        val annotationPattern = Regex(
            """@(?:[a-z_][A-Za-z0-9_]*\.)+[A-Z_][A-Za-z0-9_]*(?:\.[A-Z_][A-Za-z0-9_]*)*""",
        )
        val qualifiedTypePattern = Regex(
            """(?<![A-Za-z0-9_.])(?:[a-z_][A-Za-z0-9_]*\.){2,}[A-Z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""",
        )
        val importedTypePattern = Regex(
            """(?m)^\s*import\s+((?:[a-z_][A-Za-z0-9_]*\.)+[A-Z_][A-Za-z0-9_]*(?:\.[A-Z_][A-Za-z0-9_]*)*)\s*;""",
        )
        val declaredTypePattern = Regex(
            """\b(?:class|interface|enum|record|@interface)\s+([A-Z_][A-Za-z0-9_]*)\b""",
        )
        val collisionMarker = Regex(
            """//\s*fqcn-import-check:\s*allow\s*--\s*simple-name collision:\s*([A-Z_][A-Za-z0-9_]*)\s*$""",
        )
        val violations = mutableListOf<JavaImportStyleViolation>()

        javaImportStyleSources.files.sortedBy { source -> source.absolutePath }.forEach { source ->
            val relativePath = rootDir.toPath().relativize(source.toPath()).toString().replace('\\', '/')
            val original = source.readText(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
            val code = stripJavaLiteralsAndComments(original)
            val originalLines = original.lines()
            val codeLines = code.lines()
            val annotationStarts = mutableSetOf<Int>()
            val importedTypesBySimpleName = importedTypePattern.findAll(code)
                .map { match -> match.groupValues[1] }
                .groupBy { importedType -> importedType.substringAfterLast('.') }
            val declaredTypeNames = declaredTypePattern.findAll(code)
                .map { match -> match.groupValues[1] }
                .toSet()

            annotationPattern.findAll(code).forEach { match ->
                val line = lineNumberAt(code, match.range.first)
                annotationStarts.add(match.range.first + 1)
                violations.add(JavaImportStyleViolation(
                    relativePath,
                    line,
                    "fully qualified annotation must use an import: ${match.value}",
                ))
            }

            if (!relativePath.startsWith("apps/")) return@forEach

            val qualifiedTypesByLine = mutableMapOf<Int, MutableList<String>>()
            qualifiedTypePattern.findAll(code).forEach { match ->
                if (match.range.first in annotationStarts) return@forEach
                val line = lineNumberAt(code, match.range.first)
                val codeLine = codeLines.getOrElse(line - 1) { "" }.trimStart()
                if (codeLine.startsWith("package ") || codeLine.startsWith("import ")) return@forEach
                qualifiedTypesByLine.getOrPut(line) { mutableListOf() }.add(match.value)
                val originalLine = originalLines.getOrElse(line - 1) { "" }
                val markerType = collisionMarker.find(originalLine)?.groupValues?.get(1)
                val segments = match.value.split('.')
                val typeSegmentIndex = segments.indexOfFirst { segment ->
                    segment.firstOrNull()?.let { first -> first == '_' || first.isUpperCase() } == true
                }
                val simpleName = segments.getOrNull(typeSegmentIndex)
                val referencedType = if (typeSegmentIndex >= 0) {
                    segments.take(typeSegmentIndex + 1).joinToString(".")
                } else {
                    match.value
                }
                val hasImportedCollision = importedTypesBySimpleName[simpleName]
                    .orEmpty()
                    .any { importedType -> importedType != referencedType }
                val hasDeclaredCollision = simpleName in declaredTypeNames
                if (markerType == simpleName && (hasImportedCollision || hasDeclaredCollision)) return@forEach
                violations.add(JavaImportStyleViolation(
                    relativePath,
                    line,
                    "avoidable fully qualified type must use an import: ${match.value}",
                ))
            }

            originalLines.forEachIndexed { lineIndex, line ->
                val marker = collisionMarker.find(line) ?: return@forEachIndexed
                val qualifiedTypes = qualifiedTypesByLine[lineIndex + 1].orEmpty()
                val markerType = marker.groupValues[1]
                val hasMatchingQualifiedType = qualifiedTypes.any { qualifiedType ->
                    qualifiedType.split('.').firstOrNull { segment ->
                        segment.firstOrNull()?.let { first -> first == '_' || first.isUpperCase() } == true
                    } == markerType
                }
                if (!hasMatchingQualifiedType) {
                    violations.add(JavaImportStyleViolation(
                        relativePath,
                        lineIndex + 1,
                        "stale FQCN collision allowance must be removed",
                    ))
                }
            }
        }

        check(violations.isEmpty()) {
            buildString {
                appendLine("Java import style violations:")
                violations.sortedWith(compareBy({ it.path }, { it.line }, { it.message })).forEach { violation ->
                    appendLine("${violation.path}:${violation.line}: ${violation.message}")
                }
                append("Only an actual app simple-name collision may use " +
                        "'// fqcn-import-check: allow -- simple-name collision: <type>' on the same line.")
            }
        }
    }
}

subprojects {
    if (!buildFile.exists()) {
        return@subprojects
    }

    apply(plugin = "java-library")
    productionClasses.from(extensions.getByType<SourceSetContainer>().named("main").map { it.output.classesDirs })

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        add("testImplementation", platform(junitBom))
        add("testImplementation", junitJupiter)
        add("testRuntimeOnly", junitPlatformLauncher)
    }

    tasks.withType<Test>().configureEach {
        dependsOn(rootProject.tasks.named("architectureTest"))
        useJUnitPlatform()
    }
}

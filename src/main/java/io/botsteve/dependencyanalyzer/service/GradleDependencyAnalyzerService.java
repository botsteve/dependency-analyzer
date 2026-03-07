package io.botsteve.dependencyanalyzer.service;

import static io.botsteve.dependencyanalyzer.utils.JavaVersionResolver.JDKS;
import static io.botsteve.dependencyanalyzer.utils.JavaVersionResolver.resolveJavaPathToBeUsed;
import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.fixNonResolvableScmRepositorise;
import static io.botsteve.dependencyanalyzer.utils.Utils.getPropertyFromSetting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyzes dependencies for Gradle projects.
 * 
 * Primary strategy: Uses the CycloneDX Gradle plugin via an init script (no project
 * modification needed) to generate a BOM with dependencies AND SCM URLs.
 * 
 * Fallback strategy: For older Gradle versions incompatible with CycloneDX 3.x,
 * falls back to parsing `gradle dependencies` text output. SCM URLs are then
 * fetched separately from Maven Central POMs.
 */
public class GradleDependencyAnalyzerService {

  private static final Logger log = LoggerFactory.getLogger(GradleDependencyAnalyzerService.class);

  private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

  // --- Fallback parser patterns ---
  // Matches lines like: +--- group:artifact:version
  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("^([| ]*)[+\\\\]---\\s+(\\S+?):(\\S+?):(\\S+?)(?:\\s+->\\s+(\\S+))?(?:\\s+\\(\\*\\))?(?:\\s+\\(c\\))?\\s*$");

  private static final Pattern CONFIGURATION_HEADER_PATTERN =
      Pattern.compile("^(\\w+)(?:\\s+-\\s+.*)?$");

  private static final Set<String> RELEVANT_CONFIGURATIONS = Set.of(
      "implementation", "api", "compileOnly", "compileOnlyApi",
      "runtimeOnly", "runtimeClasspath", "compileClasspath",
      "testImplementation", "testCompileOnly", "testRuntimeOnly",
      "testRuntimeClasspath", "testCompileClasspath",
      "testFixturesImplementation", "testFixturesApi", "testFixturesCompileClasspath", "testFixturesRuntimeClasspath",
      "annotationProcessor", "testAnnotationProcessor"
  );

  private static final Pattern PROJECT_HEADER_PATTERN =
      Pattern.compile("^-*\\s*Project\\s+[':]*([^']*?)(?:'?)\\s*-*$");

  /**
   * Whether the last getDependencies() call used CycloneDX (true)
   * or the fallback parser (false). Used by the loading task to decide
   * whether to fetch SCM URLs separately.
   */
  private static volatile boolean lastRunUsedCycloneDx = false;

  /**
   * Returns true if the last getDependencies call used CycloneDX
   * (meaning SCM URLs are already populated).
   */
  public static boolean wasLastRunCycloneDx() {
    return lastRunUsedCycloneDx;
  }

  /**
   * Maps Gradle configuration names to simplified scope labels.
   */
  private static String mapConfigurationToScope(String configuration) {
    if (configuration == null) return "";
    return switch (configuration) {
      case "implementation", "compileClasspath" -> "implementation";
      case "api" -> "api";
      case "compileOnly" -> "compileOnly";
      case "compileOnlyApi" -> "compileOnlyApi";
      case "runtimeOnly" -> "runtimeOnly";
      case "runtimeClasspath" -> "runtime";
      case "testImplementation", "testCompileClasspath" -> "testImplementation";
      case "testCompileOnly" -> "testCompileOnly";
      case "testRuntimeOnly" -> "testRuntimeOnly";
      case "testRuntimeClasspath" -> "testRuntime";
      case "annotationProcessor" -> "annotationProcessor";
      case "testAnnotationProcessor" -> "testAnnotationProcessor";
      default -> configuration;
    };
  }

  /**
   * Extracts all dependencies from a Gradle project directory.
   * 
   * Tries CycloneDX first for a clean BOM with SCM URLs.
   * Falls back to `gradle dependencies` parsing for older Gradle versions.
   */
  private static final String CYCLONEDX_PLUGIN_V3 = "4.0.3";
  // used for gradle < 8.0
  private static final String CYCLONEDX_PLUGIN_LEGACY = "1.8.2";

  /**
   * Extracts all dependencies from a Gradle project directory.
   * 
   * Tries CycloneDX first (dynamically selecting plugin version based on Gradle version).
   * Falls back to `gradle dependencies` parsing if that fails.
   */
  public static Set<DependencyNode> getDependencies(String projectDir) throws Exception {
    int gradleMajorVersion = detectGradleMajorVersion(new File(projectDir));
    boolean useLegacyPlugin = gradleMajorVersion < 8;

    try {
      if (useLegacyPlugin) {
        log.info("Detected Gradle version {} (< 8), using legacy CycloneDX plugin {}", gradleMajorVersion, CYCLONEDX_PLUGIN_LEGACY);
      } else {
        log.info("Detected Gradle version {} (8+), using modern CycloneDX plugin {}", gradleMajorVersion, CYCLONEDX_PLUGIN_V3);
      }

      Set<DependencyNode> deps = getDependenciesViaCycloneDx(projectDir, useLegacyPlugin);
      lastRunUsedCycloneDx = true;
      return deps;
    } catch (Exception e) {
      log.warn("CycloneDX approach failed, falling back to gradle dependencies parsing: {}", e.getMessage());
    }

    // Fallback: parse gradle dependencies output
    lastRunUsedCycloneDx = false;
    return getDependenciesViaGradleParsing(projectDir);
  }

  /**
   * Detects the project's Gradle major version.
   * Returns 8 (default) if version cannot be determined (optimistic).
   */
  private static int detectGradleMajorVersion(File projectDir) {
    File wrapperProps = new File(projectDir, "gradle/wrapper/gradle-wrapper.properties");
    if (!wrapperProps.exists()) {
      return 8; // Default to modern if unknown
    }

    try {
      Properties props = new Properties();
      try (FileInputStream fis = new FileInputStream(wrapperProps)) {
        props.load(fis);
      }

      String distributionUrl = props.getProperty("distributionUrl");
      if (distributionUrl == null) return 8;

      Matcher matcher = Pattern.compile("gradle-(\\d+)\\.(\\d+)")
          .matcher(distributionUrl);
      if (!matcher.find()) return 8;

      return Integer.parseInt(matcher.group(1));
    } catch (Exception e) {
      log.warn("Could not check Gradle version: {}", e.getMessage());
      return 8; // Default to modern on error
    }
  }

  // ==========================================================================
  // CycloneDX approach (primary)
  // ==========================================================================

  private static Set<DependencyNode> getDependenciesViaCycloneDx(String projectDir, boolean useLegacyPlugin) throws Exception {
    runCycloneDxBom(projectDir, useLegacyPlugin);

    // Output path differs between versions
    // V3+: build/reports/cyclonedx/bom.json
    // Legacy: build/reports/bom.json
    File bomFile;
    if (useLegacyPlugin) {
       bomFile = new File(projectDir, "build/reports/bom.json");
    } else {
       bomFile = new File(projectDir, "build/reports/cyclonedx/bom.json");
    }
    
    if (!bomFile.exists()) {
      throw new DependencyAnalyzerException("CycloneDX BOM file not found at " + bomFile.getAbsolutePath());
    }

    Set<DependencyNode> dependencies = parseCycloneDxBom(bomFile);
    log.info("Found {} dependencies from CycloneDX BOM", dependencies.size());

    if (dependencies.isEmpty()) {
      throw new DependencyAnalyzerException("No dependencies found in the CycloneDX BOM.");
    }

    return dependencies;
  }

  private static void runCycloneDxBom(String projectDir, boolean useLegacyPlugin) throws Exception {
    File dir = new File(projectDir);
    String gradleCmd = findGradleExecutable(dir);
    Path initScript = extractInitScript(useLegacyPlugin);
    List<String> jdkPaths = buildJdkCandidateList(dir);

    Exception lastException = null;
    for (String javaHome : jdkPaths) {
      try {
        executeCycloneDxBom(gradleCmd, dir, javaHome, initScript);
        return;
      } catch (Exception e) {
        lastException = e;
        log.warn("CycloneDX failed with JAVA_HOME={}: {}", javaHome, e.getMessage());
      }
    }

    throw new DependencyAnalyzerException(
        "CycloneDX BOM generation failed with all available JDKs. Last error: "
        + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
  }

  private static Path extractInitScript(boolean useLegacyPlugin) throws IOException {
    Path tempScript = Files.createTempFile("cyclonedx-init", ".gradle"); // .gradle is universal (groovy), .kts is kotlin
    tempScript.toFile().deleteOnExit();

    String scriptContent;
    if (useLegacyPlugin) {
      // Legacy Groovy script for Gradle < 8
      scriptContent = 
          "initscript {\n" +
          "    repositories {\n" +
          "        gradlePluginPortal()\n" +
          "        mavenCentral()\n" +
          "    }\n" +
          "    dependencies {\n" +
          "        classpath 'org.cyclonedx:cyclonedx-gradle-plugin:" + CYCLONEDX_PLUGIN_LEGACY + "'\n" +
          "    }\n" +
          "    configurations.classpath.resolutionStrategy.eachDependency { details ->\n" +
          "        if (details.requested.group.contains('jackson')) {\n" +
          "            details.useVersion '2.13.4'\n" +
          "        }\n" +
          "        if (details.requested.group == 'org.apache.maven' && details.requested.name == 'maven-core') {\n" +
          "            details.useVersion '3.6.3'\n" +
          "        }\n" +
          "        if (details.requested.group == 'com.google.guava' && details.requested.name == 'guava') {\n" +
          "            details.useVersion '28.2-jre'\n" +
          "        }\n" +
          "    }\n" +
          "}\n" +
          "import org.cyclonedx.gradle.CycloneDxPlugin\n" +
          "allprojects {\n" +
          "    apply plugin: CycloneDxPlugin\n" +
          "}\n";
    } else {
      // Modern Groovy script for Gradle 8+
      scriptContent = 
          "initscript {\n" +
          "    repositories {\n" +
          "        gradlePluginPortal()\n" +
          "    }\n" +
          "    dependencies {\n" +
          "        classpath 'org.cyclonedx:cyclonedx-gradle-plugin:" + CYCLONEDX_PLUGIN_V3 + "'\n" +
          "    }\n" +
          "}\n" +
          "import org.cyclonedx.gradle.CycloneDxPlugin\n" +
          "allprojects {\n" +
          "    apply plugin: CycloneDxPlugin\n" +
          "}\n";
    }

    Files.writeString(tempScript, scriptContent);
    log.info("Extracted CycloneDX init script to: {}", tempScript);
    return tempScript;
  }

  private static void executeCycloneDxBom(String gradleCmd, File projectDir,
      String javaHome, Path initScript) throws Exception {

    ArrayList<String> command = new ArrayList<>();
    command.add(gradleCmd);
    command.add("cyclonedxBom");
    command.add("--init-script");
    command.add(initScript.toAbsolutePath().toString());
    command.add("--console=plain");

    log.info("Executing CycloneDX: {} in {} with JAVA_HOME={}",
        String.join(" ", command), projectDir, javaHome);

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(projectDir);
    pb.redirectErrorStream(true);

    if (javaHome != null && !javaHome.isEmpty()) {
      pb.environment().put("JAVA_HOME", javaHome);
    }

    Process process = pb.start();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.info("[gradle-cyclonedx] {}", line);
        output.append(line).append(System.lineSeparator());
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new DependencyAnalyzerException("CycloneDX BOM generation failed (exit code " + exitCode + ")");
    }
  }

  private static Set<DependencyNode> parseCycloneDxBom(File bomFile) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode rootNode = mapper.readTree(bomFile);
    JsonNode components = rootNode.get("components");

    Set<DependencyNode> dependencies = new HashSet<>();

    if (components != null && components.isArray()) {
      for (JsonNode component : components) {
        String groupId = component.path("group").asString(null);
        String artifactId = component.path("name").asString(null);
        String version = component.path("version").asString(null);

        if (groupId == null || artifactId == null) continue;

        String scope = extractScope(component);
        DependencyNode node = new DependencyNode(groupId, artifactId,
            version != null ? version : "", scope);

        String scmUrl = extractScmUrl(component, groupId, artifactId);
        node.setScmUrl(scmUrl);

        dependencies.add(node);
      }
    }

    return dependencies;
  }

  private static String extractScope(JsonNode component) {
    // 1. Try to find Gradle-specific properties (the plugin stores configuration names here)
    JsonNode properties = component.path("properties");
    if (properties.isArray()) {
      for (JsonNode prop : properties) {
        String name = prop.path("name").asString("");
        if ("gradle:configuration".equals(name) || "gradle:configurations".equals(name)) {
          String value = prop.path("value").asString("");
          if (!value.isEmpty()) {
            // If multiple configurations, take the first one or most important
            String firstConfig = value.contains(",") ? value.split(",")[0].trim() : value;
            return mapConfigurationToScope(firstConfig);
          }
        }
      }
    }

    // 2. Fallback to standard CycloneDX scope
    String scope = component.path("scope").asString(null);
    if (scope != null && !scope.isEmpty()) {
      return switch (scope.toLowerCase()) {
        case "required" -> "implementation";
        case "optional" -> "compileOnly";
        case "excluded" -> "excluded";
        default -> scope;
      };
    }

    // 3. Default to implementation for Gradle (more modern than 'compile')
    return "implementation";
  }

  private static String extractScmUrl(JsonNode component, String groupId, String artifactId) {
    JsonNode externalReferences = component.path("externalReferences");
    if (externalReferences.isArray()) {
      for (JsonNode ref : externalReferences) {
        if ("vcs".equals(ref.path("type").asString())) {
          String url = ref.path("url").asString("");
          if (!url.isEmpty()) {
            return fixNonResolvableScmRepositorise(
                ScmUrlUtils.canonicalize(url), groupId, artifactId);
          }
        }
      }
    }
    return "SCM URL not found";
  }

  // ==========================================================================
  // Fallback: gradle dependencies parsing
  // ==========================================================================

  private static Set<DependencyNode> getDependenciesViaGradleParsing(String projectDir) throws Exception {
    Map<String, Set<DependencyNode>> configToDeps = runGradleDependencies(projectDir);

    if (configToDeps.isEmpty()) {
      throw new DependencyAnalyzerException(
          "No dependencies found in Gradle project. Make sure the project has dependencies declared.");
    }

    Map<String, DependencyNode> merged = new LinkedHashMap<>();
    for (Map.Entry<String, Set<DependencyNode>> entry : configToDeps.entrySet()) {
      for (DependencyNode node : entry.getValue()) {
        String key = node.getGroupId() + ":" + node.getArtifactId() + ":" + node.getVersion();
        if (!merged.containsKey(key)) {
          merged.put(key, node);
        }
      }
    }

    Set<DependencyNode> result = new HashSet<>(merged.values());
    log.info("Found {} unique dependencies via gradle dependencies parsing", result.size());
    return result;
  }

  private static Map<String, Set<DependencyNode>> runGradleDependencies(String projectDir) throws Exception {
    File dir = new File(projectDir);
    String gradleCmd = findGradleExecutable(dir);
    List<String> jdkPaths = buildJdkCandidateList(dir);

    Exception lastException = null;
    for (String javaHome : jdkPaths) {
      try {
        return executeGradleDependencies(gradleCmd, dir, javaHome);
      } catch (Exception e) {
        lastException = e;
        log.warn("Gradle dependencies failed with JAVA_HOME={}: {}", javaHome, e.getMessage());
      }
    }

    throw new DependencyAnalyzerException(
        "Gradle dependencies command failed with all available JDKs. Last error: "
        + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
  }

  private static List<String> detectSubprojects(String gradleCmd, File projectDir, String javaHome) {
    try {
      List<String> command = List.of(gradleCmd, "projects", "--quiet", "--console=plain");
      log.info("Detecting subprojects: {} in {}", String.join(" ", command), projectDir);

      ProcessBuilder pb = new ProcessBuilder(command);
      pb.directory(projectDir);
      pb.redirectErrorStream(true);
      if (javaHome != null && !javaHome.isEmpty()) {
        pb.environment().put("JAVA_HOME", javaHome);
      }

      Process process = pb.start();
      List<String> subprojects = new ArrayList<>();
      Pattern subprojectPattern = Pattern.compile("^[+\\\\|\\s]*---\\s+Project\\s+'(:[^']+)'");

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Matcher m = subprojectPattern.matcher(line);
          if (m.find()) {
            subprojects.add(m.group(1));
          }
        }
      }
      process.waitFor();
      log.info("Detected {} subprojects: {}", subprojects.size(), subprojects);
      return subprojects;
    } catch (Exception e) {
      log.warn("Could not detect subprojects: {}", e.getMessage());
      return List.of();
    }
  }

  private static Map<String, Set<DependencyNode>> executeGradleDependencies(String gradleCmd, File projectDir, String javaHome) throws Exception {
    List<String> subprojects = detectSubprojects(gradleCmd, projectDir, javaHome);

    ArrayList<String> command = new ArrayList<>();
    command.add(gradleCmd);

    if (subprojects.isEmpty()) {
      command.add("dependencies");
    } else {
      command.add("dependencies");
      for (String subproject : subprojects) {
        command.add(subproject + ":dependencies");
      }
    }
    command.add("--console=plain");
    command.add("--info");

    log.info("Executing Gradle: {} in {} with JAVA_HOME={}", String.join(" ", command), projectDir, javaHome);

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(projectDir);
    pb.redirectErrorStream(true);

    if (javaHome != null && !javaHome.isEmpty()) {
      pb.environment().put("JAVA_HOME", javaHome);
    }

    Process process;
    try {
      process = pb.start();
    } catch (Exception e) {
      throw new DependencyAnalyzerException("Failed to start Gradle.", e);
    }

    Map<String, Set<DependencyNode>> result;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      result = parseDependenciesFromStream(reader);
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new DependencyAnalyzerException("Gradle dependencies command failed (exit code " + exitCode + ")");
    }

    return result;
  }

  static Map<String, Set<DependencyNode>> parseDependenciesFromStream(BufferedReader reader) throws IOException {
    Map<String, Set<DependencyNode>> result = new LinkedHashMap<>();
    String currentConfig = null;
    String currentProject = "";
    ArrayList<String> currentLines = new ArrayList<>();

    String line;
    while ((line = reader.readLine()) != null) {
      log.info("[gradle] {}", line);
      String trimmedLine = line.trim();
      Matcher projectMatcher = PROJECT_HEADER_PATTERN.matcher(trimmedLine);
      if (projectMatcher.matches()) {
        flushConfigSection(result, currentConfig, currentLines, currentProject);
        currentConfig = null;
        currentLines = new ArrayList<>();

        currentProject = projectMatcher.group(1).trim();
        if (currentProject.startsWith(":")) {
          currentProject = currentProject.substring(1);
        }
        log.info("Parsing dependencies for project: {}", currentProject);
        continue;
      }

      if (!line.startsWith(" ") && !line.startsWith("|") && !line.startsWith("+") && !line.startsWith("\\")) {
        Matcher headerMatcher = CONFIGURATION_HEADER_PATTERN.matcher(trimmedLine);
        if (headerMatcher.matches()) {
          String configName = headerMatcher.group(1);
          flushConfigSection(result, currentConfig, currentLines, currentProject);
          if (RELEVANT_CONFIGURATIONS.contains(configName)) {
            log.info("Parsing Gradle configuration section: {}{}",
                currentProject.isEmpty() ? "" : currentProject + "/", configName);
            currentConfig = configName;
            currentLines = new ArrayList<>();
          } else {
            currentConfig = null;
            currentLines = new ArrayList<>();
          }
        }
      } else if (currentConfig != null) {
        currentLines.add(line);
      }
    }

    flushConfigSection(result, currentConfig, currentLines, currentProject);
    return result;
  }

  private static void flushConfigSection(Map<String, Set<DependencyNode>> result,
      String config, List<String> lines, String project) {
    if (config == null || lines.isEmpty()) return;

    String effectiveConfig = config;
    Set<DependencyNode> deps = parseDependencyTree(lines, effectiveConfig);
    if (!deps.isEmpty()) {
      String mapKey = (project != null && !project.isEmpty()) ? project + "/" + config : config;
      result.computeIfAbsent(mapKey, k -> new HashSet<>()).addAll(deps);
    }
  }

  static Set<DependencyNode> parseDependencyTree(List<String> lines, String configuration) {
    Set<DependencyNode> topLevelDependencies = new HashSet<>();
    Deque<NodeAtDepth> stack = new ArrayDeque<>();
    String scope = mapConfigurationToScope(configuration);

    for (String line : lines) {
      Matcher matcher = DEPENDENCY_PATTERN.matcher(line);
      if (!matcher.matches()) continue;

      String indent = matcher.group(1);
      String groupId = matcher.group(2);
      String artifactId = matcher.group(3);
      String version = matcher.group(4);
      String resolvedVersion = matcher.group(5);

      if (resolvedVersion != null && !resolvedVersion.isEmpty()) {
        version = resolvedVersion;
      }
      version = cleanVersion(version);

      int depth = calculateDepth(indent);
      DependencyNode node = new DependencyNode(groupId, artifactId, version, scope);

      while (!stack.isEmpty() && stack.peek().depth >= depth) {
        stack.pop();
      }

      if (stack.isEmpty()) {
        topLevelDependencies.add(node);
      } else {
        NodeAtDepth parent = stack.peek();
        if (parent.node.getChildren() == null) {
          parent.node.setChildren(new ArrayList<>());
        }
        parent.node.getChildren().add(node);
      }

      stack.push(new NodeAtDepth(node, depth));
    }

    return topLevelDependencies;
  }

  // ==========================================================================
  // Shared utilities
  // ==========================================================================

  private static String findGradleExecutable(File projectDir) {
    String wrapperName = IS_WINDOWS ? "gradlew.bat" : "gradlew";
    File wrapper = new File(projectDir, wrapperName);
    if (wrapper.exists()) {
      return wrapper.getAbsolutePath();
    }
    return IS_WINDOWS ? "gradle.bat" : "gradle";
  }

  private static List<String> buildJdkCandidateList(File projectDir) {
    Set<String> candidates = new LinkedHashSet<>();

    String detectedJavaVersion = detectJavaVersionFromGradleWrapper(projectDir);
    if (detectedJavaVersion != null) {
      String detectedJdkPath = resolveJavaPathToBeUsed(detectedJavaVersion);
      if (detectedJdkPath != null && !detectedJdkPath.isEmpty()) {
        log.info("Detected Gradle-compatible Java version: {} -> JAVA_HOME={}",
            detectedJavaVersion, detectedJdkPath);
        candidates.add(detectedJdkPath);
      }
    }

    String currentJavaHome = System.getenv("JAVA_HOME");
    if (currentJavaHome != null && !currentJavaHome.isEmpty()) {
      candidates.add(currentJavaHome);
    }

    for (String jdkProperty : JDKS) {
      String jdkPath = getPropertyFromSetting(jdkProperty);
      if (jdkPath != null && !jdkPath.isEmpty()) {
        candidates.add(jdkPath);
      }
    }

    log.info("JDK candidate list (in order): {}", candidates);
    return new ArrayList<>(candidates);
  }

  /**
   * Detects Gradle wrapper version and maps it to a compatible Java version token.
   *
   * @param projectDir Gradle project directory
   * @return Java version token (for example {@code 17.0}) or {@code null} when detection fails
   */
  public static String detectJavaVersionFromGradleWrapper(File projectDir) {
    File wrapperProps = new File(projectDir, "gradle/wrapper/gradle-wrapper.properties");
    if (!wrapperProps.exists()) {
      log.info("No gradle-wrapper.properties found, cannot detect Gradle version");
      return null;
    }

    try {
      Properties props = new Properties();
      try (FileInputStream fis = new FileInputStream(wrapperProps)) {
        props.load(fis);
      }

      String distributionUrl = props.getProperty("distributionUrl");
      if (distributionUrl == null) {
        log.info("No distributionUrl found in gradle-wrapper.properties");
        return null;
      }

      Matcher matcher = Pattern.compile("gradle-(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
          .matcher(distributionUrl);
      if (!matcher.find()) {
        log.info("Could not parse Gradle version from distributionUrl: {}", distributionUrl);
        return null;
      }

      int major = Integer.parseInt(matcher.group(1));
      int minor = Integer.parseInt(matcher.group(2));
      log.info("Detected Gradle wrapper version: {}.{}", major, minor);

      return mapGradleVersionToJavaVersion(major, minor);

    } catch (Exception e) {
      log.warn("Failed to read gradle-wrapper.properties: {}", e.getMessage());
      return null;
    }
  }

  private static String mapGradleVersionToJavaVersion(int major, int minor) {
    if (major >= 8 && minor >= 5) return "21.0";
    if (major >= 8) return "17.0";
    if (major == 7 && minor >= 3) return "17.0";
    if (major == 7) return "17.0";
    if (major == 6) return "11.0";
    if (major == 5) return "11.0";
    return "1.8";
  }

  private static int calculateDepth(String indent) {
    if (indent == null || indent.isEmpty()) return 0;
    return indent.length() / 5;
  }

  private static String cleanVersion(String version) {
    if (version == null) return "";
    version = version.replaceAll("\\{strictly\\s+", "").replace("}", "");
    return version.trim();
  }

  private static class NodeAtDepth {
    final DependencyNode node;
    final int depth;
    NodeAtDepth(DependencyNode node, int depth) {
      this.node = node;
      this.depth = depth;
    }
  }
}

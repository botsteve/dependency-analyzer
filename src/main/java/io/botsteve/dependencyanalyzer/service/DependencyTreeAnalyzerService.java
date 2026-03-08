package io.botsteve.dependencyanalyzer.service;

import static io.botsteve.dependencyanalyzer.service.MavenInvokerService.getMavenInvokerResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;
import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import io.botsteve.dependencyanalyzer.model.CollectingOutputHandler;
import io.botsteve.dependencyanalyzer.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyTreeAnalyzerService {

  private static final Logger log = LoggerFactory.getLogger(DependencyTreeAnalyzerService.class);
  private static final Pattern MAVEN_LOG_PREFIX_PATTERN = Pattern.compile("^\\s*(?:\\[[^\\]]+]\\s*)+");
  private static final String DEPENDENCY_TREE_OUTPUT_FILE = "target/dependency-analyzer-tree.json";

  /**
   * Returns module list from parent pom.xml.
   *
   * @param projectDir Maven project root directory
   * @return declared module names
   * @throws Exception when POM parsing fails
   */
  public static List<String> getModules(String projectDir) throws Exception {
    File parentPomFile = new File(projectDir, "pom.xml");
    List<String> modules = Utils.parseModulesFromPom(parentPomFile);
    log.info("Found {} modules in project: {}", modules.size(), modules);
    return modules;
  }

  /**
   * Executes Maven dependency:tree in JSON mode for root or module scope.
   *
   * @param projectDir Maven project root directory
   * @param moduleDir module path (empty for root)
   * @return raw dependency tree JSON read from plugin output file
   */
  public static String runMavenDependencyTree(String projectDir, String moduleDir) {
    log.info("Running Maven dependency:tree for module '{}' in {}", moduleDir.isEmpty() ? "(root)" : moduleDir, projectDir);

    File moduleBaseDir = moduleDir.isEmpty() ? new File(projectDir) : new File(projectDir, moduleDir);
    File dependencyTreeOutputFile = new File(moduleBaseDir, DEPENDENCY_TREE_OUTPUT_FILE);
    deleteExistingOutputFile(dependencyTreeOutputFile);

    CollectingOutputHandler outputHandler = getMavenInvokerResult(projectDir, moduleDir,
        "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree",
        "-DoutputType=json -DoutputFile=" + DEPENDENCY_TREE_OUTPUT_FILE,
        System.getenv("JAVA_HOME"), false);

    List<String> outputLines = outputHandler.getOutput();
    log.info("Maven dependency:tree produced {} output lines for module '{}'", outputLines.size(), moduleDir.isEmpty() ? "(root)" : moduleDir);
    String json = readDependencyTreeJsonFromFile(dependencyTreeOutputFile, outputLines);
    log.info("Extracted JSON ({} chars) from Maven output for module '{}'", json.length(), moduleDir.isEmpty() ? "(root)" : moduleDir);
    return json;
  }

  private static void deleteExistingOutputFile(File outputFile) {
    try {
      Files.deleteIfExists(outputFile.toPath());
    } catch (IOException e) {
      log.debug("Could not remove previous dependency tree output file {}: {}", outputFile.getAbsolutePath(), e.getMessage());
    }
  }

  static String readDependencyTreeJsonFromFile(File outputFile, List<String> outputLines) {
    if (!outputFile.exists()) {
      throw new DependencyAnalyzerException(
          "Maven dependency tree JSON file was not generated: " + outputFile.getAbsolutePath()
          + ". Last Maven output lines:\n" + getLastOutputLines(outputLines, 8));
    }

    try {
      String json = Files.readString(outputFile.toPath()).trim();
      if (json.isBlank()) {
        throw new DependencyAnalyzerException(
            "Maven dependency tree JSON file is empty: " + outputFile.getAbsolutePath()
            + ". Last Maven output lines:\n" + getLastOutputLines(outputLines, 8));
      }
      return json;
    } catch (IOException e) {
      throw new DependencyAnalyzerException(
          "Failed to read Maven dependency tree JSON file: " + outputFile.getAbsolutePath(), e);
    }
  }

  private static String getLastOutputLines(List<String> outputLines, int maxLines) {
    int start = Math.max(0, outputLines.size() - maxLines);
    StringBuilder builder = new StringBuilder();
    for (int i = start; i < outputLines.size(); i++) {
      builder.append(outputLines.get(i)).append(System.lineSeparator());
    }
    return builder.toString().trim();
  }

  static String extractJsonFromMavenOutput(List<String> outputLines) {
    StringBuilder jsonBuilder = new StringBuilder();
    boolean inJson = false;
    int braceDepth = 0;
    boolean inString = false;
    boolean escaped = false;

    for (String line : outputLines) {
      String strippedLine = stripMavenLogPrefix(line).trim();
      if (!inJson) {
        if (!strippedLine.startsWith("{")) {
          continue;
        }
        inJson = true;
      }

      for (int i = 0; i < strippedLine.length(); i++) {
        char current = strippedLine.charAt(i);
        jsonBuilder.append(current);

        if (escaped) {
          escaped = false;
          continue;
        }

        if (current == '\\' && inString) {
          escaped = true;
          continue;
        }

        if (current == '"') {
          inString = !inString;
          continue;
        }

        if (!inString) {
          if (current == '{') {
            braceDepth++;
          } else if (current == '}') {
            braceDepth--;
            if (braceDepth < 0) {
              throw new DependencyAnalyzerException("Malformed Maven dependency JSON: unmatched closing brace.");
            }
            if (braceDepth == 0) {
              return jsonBuilder.toString().trim();
            }
          }
        }
      }

      jsonBuilder.append(System.lineSeparator());
    }

    if (!inJson) {
      throw new DependencyAnalyzerException("Failed to find JSON payload in Maven dependency:tree output.");
    }
    throw new DependencyAnalyzerException("Failed to extract complete JSON payload from Maven dependency:tree output (incomplete JSON object).");
  }

  private static String stripMavenLogPrefix(String line) {
    return MAVEN_LOG_PREFIX_PATTERN.matcher(line).replaceFirst("");
  }
}

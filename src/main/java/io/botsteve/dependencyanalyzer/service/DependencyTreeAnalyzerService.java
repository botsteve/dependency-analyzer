package io.botsteve.dependencyanalyzer.service;

import static io.botsteve.dependencyanalyzer.service.MavenInvokerService.getMavenInvokerResult;

import java.io.File;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import io.botsteve.dependencyanalyzer.utils.Utils;

@Slf4j
public class DependencyTreeAnalyzerService {

  public static List<String> getModules(String projectDir) throws Exception {
    File parentPomFile = new File(projectDir, "pom.xml");
    List<String> modules = Utils.parseModulesFromPom(parentPomFile);
    log.info("Found {} modules in project: {}", modules.size(), modules);
    return modules;
  }

  public static String runMavenDependencyTree(String projectDir, String moduleDir) {
    log.info("Running Maven dependency:tree for module '{}' in {}", moduleDir.isEmpty() ? "(root)" : moduleDir, projectDir);
    var outputHandler = getMavenInvokerResult(projectDir, moduleDir,
                                              "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree",
                                              "-DoutputType=json", System.getenv("JAVA_HOME"));
    List<String> outputLines = outputHandler.getOutput();
    log.info("Maven dependency:tree produced {} output lines for module '{}'", outputLines.size(), moduleDir.isEmpty() ? "(root)" : moduleDir);
    String json = extractJsonFromMavenOutput(outputLines);
    log.info("Extracted JSON ({} chars) from Maven output for module '{}'", json.length(), moduleDir.isEmpty() ? "(root)" : moduleDir);
    return json;
  }

  private static String extractJsonFromMavenOutput(List<String> outputLines) {
    StringBuilder jsonBuilder = new StringBuilder();
    boolean inJson = false;

    for (String line : outputLines) {
      // Remove the "[INFO]" prefix
      String strippedLine = line.replaceFirst("^\\[INFO] ?", "");
      if (strippedLine.trim().startsWith("{")) {
        inJson = true;
      }
      if (inJson) {
        jsonBuilder.append(strippedLine).append(System.lineSeparator());
      }
    }

    return jsonBuilder.toString().trim();
  }
}


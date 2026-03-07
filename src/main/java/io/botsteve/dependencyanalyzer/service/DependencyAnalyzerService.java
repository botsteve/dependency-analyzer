package io.botsteve.dependencyanalyzer.service;


import static io.botsteve.dependencyanalyzer.service.DependencyTreeAnalyzerService.getModules;
import static io.botsteve.dependencyanalyzer.service.DependencyTreeAnalyzerService.runMavenDependencyTree;
import static io.botsteve.dependencyanalyzer.utils.Utils.getProjectName;

import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.model.ProjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyAnalyzerService {

  private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzerService.class);
  private static final Map<String, DependencyNode> moduleToDependencyNode = new HashMap<>();

  /**
   * Detects the project type and delegates to the appropriate analyzer.
   */
  public static Set<DependencyNode> getDependencies(String projectDir) throws Exception {
    ProjectType projectType = ProjectType.detect(new File(projectDir));
    log.info("Detected project type: {} for directory: {}", projectType, projectDir);

    return switch (projectType) {
      case MAVEN -> getMavenDependencies(projectDir);
      case GRADLE -> GradleDependencyAnalyzerService.getDependencies(projectDir);
      default -> throw new DependencyAnalyzerException(
          "No recognizable build file found (pom.xml, build.gradle, settings.gradle). " +
          "Please open the root directory of a Maven or Gradle project.");
    };
  }

  /**
   * Returns the detected project type for the given directory.
   */
  public static ProjectType getProjectType(String projectDir) {
    return ProjectType.detect(new File(projectDir));
  }

  /**
   * Analyzes Maven dependencies for root and modules, then removes module self-artifacts.
   *
   * @param projectDir Maven project directory
   * @return aggregated dependency roots excluding module artifacts
   * @throws Exception when Maven tree extraction or JSON parsing fails
   */
  private static Set<DependencyNode> getMavenDependencies(String projectDir) throws Exception {
    List<String> modules = getModules(projectDir);
    ObjectMapper objectMapper = new ObjectMapper();
    DependencyNode rootDependencies = objectMapper.readValue(runMavenDependencyTree(projectDir, ""), DependencyNode.class);
    moduleToDependencyNode.put(getProjectName(new File(projectDir, "pom.xml")), rootDependencies);
    Set<DependencyNode> totalDependencies = new HashSet<>(new HashSet<>(rootDependencies.getChildren()));

    for (String module : modules) {
      String dependencyTreeJson = runMavenDependencyTree(projectDir, module);
      DependencyNode dependencyNode = objectMapper.readValue(dependencyTreeJson, DependencyNode.class);
      moduleToDependencyNode.put(module, dependencyNode);
      totalDependencies.addAll(new HashSet<>(dependencyNode.getChildren()));
    }
    return totalDependencies.stream()
               .filter(dependencyNode -> !modules.contains(dependencyNode.getArtifactId()))
               .collect(Collectors.toSet());
  }
}

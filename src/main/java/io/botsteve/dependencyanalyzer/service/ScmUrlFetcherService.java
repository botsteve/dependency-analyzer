package io.botsteve.dependencyanalyzer.service;

import static io.botsteve.dependencyanalyzer.service.MavenInvokerService.getMavenInvokerResult;
import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.fixNonResolvableScmRepositorise;

import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class ScmUrlFetcherService {


  private static final String CYCLONEDX_MAVEN = "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom";
  private static final String MAVEN_OPTS = "-DincludeTestScope=true -DoutputFormat=json";
  private static final String SCM_URL_NOT_FOUND = "SCM URL not found";

  /**
   * Generates CycloneDX BOM for a Maven project and populates dependency SCM URLs from it.
   *
   * @param projectDir   Maven project directory
   * @param dependencies dependency nodes that will be updated in place
   *
   * @throws IOException when BOM reading fails
   */
  public static void fetchScmUrls(String projectDir, Set<DependencyNode> dependencies) throws IOException {
    getMavenInvokerResult(projectDir, "", CYCLONEDX_MAVEN, MAVEN_OPTS, System.getenv("JAVA_HOME"));
    populateVcsUrls(dependencies, parseBomFile(projectDir + "/target/bom.json"));
  }


  private static Map<String, String> parseBomFile(String bomFilePath) throws IOException {
    Map<String, String> vcsUrlMap = new HashMap<>();
    File bomFile = new File(bomFilePath);

    ObjectMapper mapper = new ObjectMapper();
    JsonNode rootNode = mapper.readTree(bomFile);
    JsonNode components = rootNode.get("components");

    if (components != null && components.isArray()) {
      for (JsonNode component : components) {
        String groupId = component.path("group").asString();
        String artifactId = component.path("name").asString();
        String version = component.path("version").asString();
        String vcsUrl = SCM_URL_NOT_FOUND;

        JsonNode externalReferences = component.path("externalReferences");
        if (externalReferences.isArray()) {
          for (JsonNode ref : externalReferences) {
            if ("vcs".equals(ref.path("type").asString())) {
              vcsUrl = ref.path("url").asString();
              break;
            }
          }
        }

        String key = groupId + ":" + artifactId + ":" + version;
        String normalizedVcsUrl = SCM_URL_NOT_FOUND.equals(vcsUrl)
            ? SCM_URL_NOT_FOUND
            : ScmUrlUtils.canonicalize(vcsUrl);
        vcsUrlMap.put(key, fixNonResolvableScmRepositorise(normalizedVcsUrl, groupId, artifactId));
      }
    }

    return vcsUrlMap;
  }

  private static void populateVcsUrls(Set<DependencyNode> dependencies, Map<String, String> vcsUrls) {
    for (DependencyNode node : dependencies) {
      String key = node.getGroupId() + ":" + node.getArtifactId() + ":" + node.getVersion();
      if (vcsUrls.containsKey(key)) {
        node.setScmUrl(vcsUrls.get(key));
      }
      if (node.getChildren() != null) {
        populateVcsUrls(new HashSet<>(node.getChildren()), vcsUrls);
      }
    }
  }
}

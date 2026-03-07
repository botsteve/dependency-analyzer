package io.botsteve.dependencyanalyzer.service;

import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.fixNonResolvableScmRepositorise;
import static io.botsteve.dependencyanalyzer.service.MavenInvokerService.getMavenInvokerResult;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.maven.shared.invoker.MavenInvocationException;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import org.xml.sax.SAXException;

public class ScmUrlFetcherService {


  private static final String CYCLONEDX_MAVEN = "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom";
  private static final String MAVEN_OPTS = "-DincludeTestScope=true -DoutputFormat=json";
  private static final String SCM_URL_NOT_FOUND = "SCM URL not found";

  public static void fetchScmUrls(String projectDir, Set<DependencyNode> dependencies)
      throws ParserConfigurationException, IOException, SAXException, MavenInvocationException {
    getMavenInvokerResult(projectDir, "", CYCLONEDX_MAVEN, MAVEN_OPTS, System.getenv("JAVA_HOME"));
    populateVcsUrls(dependencies, parseBomFile(projectDir + "/target/bom.json"));
  }


  private static Map<String, String> parseBomFile(String bomFilePath) throws IOException {
    Map<String, String> vcsUrlMap = new HashMap<>();
    File bomFile = new File(bomFilePath);
    
    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(bomFile);
    com.fasterxml.jackson.databind.JsonNode components = rootNode.get("components");

    if (components != null && components.isArray()) {
      for (com.fasterxml.jackson.databind.JsonNode component : components) {
        String groupId = component.path("group").asText();
        String artifactId = component.path("name").asText();
        String version = component.path("version").asText();
        String vcsUrl = SCM_URL_NOT_FOUND;

        com.fasterxml.jackson.databind.JsonNode externalReferences = component.path("externalReferences");
        if (externalReferences.isArray()) {
          for (com.fasterxml.jackson.databind.JsonNode ref : externalReferences) {
            if ("vcs".equals(ref.path("type").asText())) {
              vcsUrl = ref.path("url").asText();
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

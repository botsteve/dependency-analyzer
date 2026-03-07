package io.botsteve.dependencyanalyzer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.botsteve.dependencyanalyzer.model.DependencyNode;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class LicenseAggregationServicePathResolutionTest {

  @Test
  void shouldResolveRepoNameFromScmUrl() throws Exception {
    DependencyNode node = new DependencyNode("g", "artifact-fallback", "1.0.0");
    node.setScmUrl("https://gitbox.apache.org/repos/asf?p=commons-io.git");
    LicenseAggregationService.DependencyCoordinate coordinate = new LicenseAggregationService.DependencyCoordinate("g", "artifact-fallback", "1.0.0");

    Method method = LicenseAggregationService.class.getDeclaredMethod(
        "resolveRepoName", DependencyNode.class, LicenseAggregationService.DependencyCoordinate.class);
    method.setAccessible(true);

    String repoName = (String) method.invoke(null, node, coordinate);
    assertEquals("commons-io", repoName);
  }
}

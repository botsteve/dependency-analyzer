package io.botsteve.dependencyanalyzer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.botsteve.dependencyanalyzer.model.DependencyNode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UtilsVersionComparisonTest {

  @Test
  void shouldHandleQualifierVersions() {
    assertTrue(Utils.compareVersions("2.0.Final", "2.0.RC2") > 0);
    assertTrue(Utils.compareVersions("1.0.0", "1.0.0-beta") > 0);
  }

  @Test
  void shouldHandleNullAndBlankSafely() {
    assertEquals(0, Utils.compareVersions(null, ""));
    assertTrue(Utils.compareVersions("1.0", null) > 0);
  }

  @Test
  void shouldCollectLatestQualifierAwareVersion() {
    DependencyNode d1 = new DependencyNode("g", "a", "2.0.RC2");
    d1.setScmUrl("https://example/repo");
    DependencyNode d2 = new DependencyNode("g", "a", "2.0.Final");
    d2.setScmUrl("https://example/repo");
    Set<DependencyNode> deps = new LinkedHashSet<>();
    deps.add(d1);
    deps.add(d2);

    Map<String, String> latest = Utils.collectLatestVersions(deps);
    assertEquals("2.0.Final", latest.get("https://example/repo"));
  }
}

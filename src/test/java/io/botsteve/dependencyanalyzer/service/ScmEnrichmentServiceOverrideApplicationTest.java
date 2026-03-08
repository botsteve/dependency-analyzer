package io.botsteve.dependencyanalyzer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.botsteve.dependencyanalyzer.model.DependencyNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScmEnrichmentServiceOverrideApplicationTest {

  private static final String OVERRIDES_FILE_PROPERTY = "dependency.analyzer.scm.overrides.file";

  @TempDir
  Path tempDir;

  @AfterEach
  void cleanup() {
    System.clearProperty(OVERRIDES_FILE_PROPERTY);
  }

  @Test
  void shouldApplyArtifactOverrideEvenWhenNodeAlreadyHasScmUrl() throws Exception {
    Path overrides = tempDir.resolve("scm-overrides.properties");
    Files.writeString(overrides,
        "artifact.opencsv=https://git.code.sf.net/p/opencsv/source\n",
        StandardCharsets.UTF_8);
    System.setProperty(OVERRIDES_FILE_PROPERTY, overrides.toString());

    DependencyNode node = new DependencyNode("com.opencsv", "opencsv", "5.12.0");
    node.setScmUrl("https://sourceforge.net/p/opencsv/source/ci/master/tree");

    ScmEnrichmentService.applyOverridesInPlace(Set.of(node));

    assertEquals("https://git.code.sf.net/p/opencsv/source", node.getScmUrl());
  }

  @Test
  void shouldApplyOverridesAtEndOfFetchScmUrlsFlow() throws Exception {
    Path overrides = tempDir.resolve("scm-overrides.properties");
    Files.writeString(overrides,
        "artifact.opencsv=https://git.code.sf.net/p/opencsv/source\n",
        StandardCharsets.UTF_8);
    System.setProperty(OVERRIDES_FILE_PROPERTY, overrides.toString());

    DependencyNode node = new DependencyNode("com.opencsv", "opencsv", "5.12.0");
    node.setScmUrl("https://sourceforge.net/p/opencsv/source/ci/master/tree");

    ScmEnrichmentService.fetchScmUrls(Set.of(node));

    assertEquals("https://git.code.sf.net/p/opencsv/source", node.getScmUrl());
  }
}

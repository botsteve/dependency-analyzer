package io.botsteve.dependencyanalyzer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScmRepositoriesOverridesTest {

  @TempDir
  Path tempDir;

  @AfterEach
  void tearDown() {
    System.clearProperty("dependency.analyzer.scm.overrides.file");
    ScmRepositories.resetForTests();
  }

  @Test
  void shouldApplyArtifactOverrideCaseInsensitivelyAndCanonicalizeTarget() throws Exception {
    Path overrides = tempDir.resolve("scm-overrides.properties");
    Files.writeString(overrides,
        "artifact.Rest-Assured=https://github.com/rest-assured/rest-assured.git\n",
        StandardCharsets.UTF_8);
    System.setProperty("dependency.analyzer.scm.overrides.file", overrides.toString());
    ScmRepositories.resetForTests();

    String resolved = ScmRepositories.fixNonResolvableScmRepositorise(
        "git@github.com:rest-assured/rest-assured.git", "io.rest-assured", "rest-assured");
    assertEquals("https://github.com/rest-assured/rest-assured", resolved);
  }

  @Test
  void shouldApplyGroupPrefixOverride() throws Exception {
    Path overrides = tempDir.resolve("scm-overrides.properties");
    Files.writeString(overrides,
        "group.io.helidon=https://github.com/helidon-io/helidon\n",
        StandardCharsets.UTF_8);
    System.setProperty("dependency.analyzer.scm.overrides.file", overrides.toString());
    ScmRepositories.resetForTests();

    String resolved = ScmRepositories.fixNonResolvableScmRepositorise(
        "https://example.invalid/repo", "io.helidon.common", "helidon-common");
    assertEquals("https://github.com/helidon-io/helidon", resolved);
  }

  @Test
  void shouldRejectLegacyUrlPrefixKeys() throws Exception {
    Path overrides = tempDir.resolve("scm-overrides.properties");
    Files.writeString(overrides,
        "url.https://gitbox.apache.org/repos/asf?p=commons-text.git=https://github.com/apache/commons-text\n",
        StandardCharsets.UTF_8);
    System.setProperty("dependency.analyzer.scm.overrides.file", overrides.toString());
    ScmRepositories.resetForTests();

    assertThrows(DependencyAnalyzerException.class,
        () -> ScmRepositories.fixNonResolvableScmRepositorise("https://x", "g", "a"));
  }
}

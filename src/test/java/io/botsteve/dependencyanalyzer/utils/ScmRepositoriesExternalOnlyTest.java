package io.botsteve.dependencyanalyzer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ScmRepositoriesExternalOnlyTest {

  private static final String OVERRIDES_FILE_PROPERTY = "dependency.analyzer.scm.overrides.file";
  private static final String BASE_DIR_PROPERTY = "dependency.analyzer.base.dir";

  @TempDir
  Path tempDir;

  @AfterEach
  public void cleanup() {
    System.clearProperty(OVERRIDES_FILE_PROPERTY);
    System.clearProperty(BASE_DIR_PROPERTY);
    ScmRepositories.resetForTests();
  }

  @Test
  public void validConfigResolvesRewrite() throws IOException {
    Path config = tempDir.resolve("scm.properties");
    writeConfig(config,
        "artifact.commons-text=https://github.com/apache/commons-text\n"
            + "group.org.yaml=https://github.com/snakeyaml/snakeyaml\n");

    System.setProperty(OVERRIDES_FILE_PROPERTY, config.toString());

    String artifactMatch = ScmRepositories.fixNonResolvableScmRepositorise(
        "https://example.org/unused", "com.example",
        "commons-text");
    String groupMatch = ScmRepositories.fixNonResolvableScmRepositorise(
        "https://example.org/unused", "org.yaml",
        "snakeyaml");

    assertEquals("https://github.com/apache/commons-text", artifactMatch);
    assertEquals("https://github.com/snakeyaml/snakeyaml", groupMatch);
  }

  @Test
  public void missingConfigIsCreatedFromClasspathTemplate() {
    Path missing = tempDir.resolve("missing-scm.properties");
    System.setProperty(OVERRIDES_FILE_PROPERTY, missing.toString());

    String resolved = ScmRepositories.fixNonResolvableScmRepositorise("https://example.org/repo", "commons-text");

    assertTrue(Files.exists(missing));
    assertEquals("https://github.com/apache/commons-text", resolved);
  }

  @Test
  public void malformedConfigFailsFast() throws IOException {
    Path config = tempDir.resolve("scm-malformed.properties");
    writeConfig(config, "url.prefix=https://github.com/apache/commons-text\n");
    System.setProperty(OVERRIDES_FILE_PROPERTY, config.toString());

    DependencyAnalyzerException exception = assertThrows(DependencyAnalyzerException.class,
        () -> ScmRepositories.fixNonResolvableScmRepositorise("https://example.org/repo", "commons-text"));

    assertTrue(exception.getMessage().contains("Unsupported SCM override key prefix 'url.'"));
  }

  @Test
  public void hotReloadAppliesValidChanges() throws Exception {
    Path config = tempDir.resolve("scm-hot-reload.properties");
    writeConfig(config, "artifact.commons-text=https://github.com/apache/commons-text\n");
    System.setProperty(OVERRIDES_FILE_PROPERTY, config.toString());

    String before = ScmRepositories.fixNonResolvableScmRepositorise("https://example.org/repo", "commons-text");
    assertEquals("https://github.com/apache/commons-text", before);

    waitForTimestampBoundary();
    writeConfig(config, "artifact.commons-text=https://github.com/apache/commons-text-updated\n");

    String after = ScmRepositories.fixNonResolvableScmRepositorise("https://example.org/repo", "commons-text");
    assertEquals("https://github.com/apache/commons-text-updated", after);
  }

  @Test
  public void reloadRejectsPartialOrMalformedWritesAtomically() throws Exception {
    Path config = tempDir.resolve("scm-reload-fail.properties");
    writeConfig(config, "artifact.commons-text=https://github.com/apache/commons-text\n");
    System.setProperty(OVERRIDES_FILE_PROPERTY, config.toString());

    String initial = ScmRepositories.fixNonResolvableScmRepositorise("https://example.org/repo", "commons-text");
    assertEquals("https://github.com/apache/commons-text", initial);

    waitForTimestampBoundary();
    writeConfig(config, "artifact.commons-text=\n");

    DependencyAnalyzerException malformedException = assertThrows(DependencyAnalyzerException.class,
        () -> ScmRepositories.fixNonResolvableScmRepositorise("https://example.org/repo", "commons-text"));
    assertTrue(malformedException.getMessage().contains("value is blank"));

    waitForTimestampBoundary();
    writeConfig(config, "artifact.commons-text=https://github.com/apache/commons-text-recovered\n");

    String recovered = ScmRepositories.fixNonResolvableScmRepositorise("https://example.org/repo", "commons-text");
    assertEquals("https://github.com/apache/commons-text-recovered", recovered);
  }

  private static void writeConfig(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  private static void waitForTimestampBoundary() throws InterruptedException {
    Thread.sleep(20L);
  }
}

package io.botsteve.dependencyanalyzer.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JdkDownloadTaskResolutionTest {

  @Test
  void shouldNormalizeSupportedOperatingSystems() {
    assertEquals("windows", JdkDownloadTask.normalizeOs("Windows 11"));
    assertEquals("linux", JdkDownloadTask.normalizeOs("Linux"));
    assertEquals("mac", JdkDownloadTask.normalizeOs("Mac OS X"));
  }

  @Test
  void shouldNormalizeSupportedArchitectures() {
    assertEquals("x64", JdkDownloadTask.normalizeArchitecture("amd64"));
    assertEquals("x64", JdkDownloadTask.normalizeArchitecture("x86_64"));
    assertEquals("aarch64", JdkDownloadTask.normalizeArchitecture("arm64"));
  }

  @Test
  void shouldResolveRequestedSettingsInCanonicalJdkOrder() {
    Set<String> requested = Set.of("JAVA17_HOME", "JAVA8_HOME");
    Set<String> resolved = JdkDownloadTask.resolveEffectiveSettings(requested);
    assertEquals(List.of("JAVA8_HOME", "JAVA17_HOME"), List.copyOf(resolved));
  }

  @Test
  void shouldBuildAdoptiumEndpointForFeatureVersion() {
    String url = JdkDownloadTask.buildAdoptiumUri(17, "linux", "x64").toString();
    assertTrue(url.contains("/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"));
  }

  @Test
  void shouldPreferArchiveExtensionFromResponsePath() {
    String extension = JdkDownloadTask.resolveArchiveExtension(
        "linux",
        "",
        URI.create("https://github.com/adoptium/jdk.tar.gz"));
    assertEquals(".tar.gz", extension);
  }

  @Test
  void shouldForceX64ForJava8EvenOnArmHosts() {
    assertEquals("x64", JdkDownloadTask.resolveDownloadArchitecture(8, "aarch64"));
    assertEquals("aarch64", JdkDownloadTask.resolveDownloadArchitecture(21, "aarch64"));
  }

  @Test
  void shouldExplainWhyJava8UsesX64OnArmHosts() {
    String message = JdkDownloadTask.buildDownloadMessage(8, "mac", "x64", "aarch64");
    assertTrue(message.contains("x64"));
    assertTrue(message.contains("not available"));
  }

  @Test
  void shouldInstallEachJdkInDedicatedFolderUnderBaseDirectory() {
    Path base = Path.of("/tmp/dependency-analyzer/downloaded_jdks");
    Path install = JdkDownloadTask.resolveInstallDirectory(base, "JAVA17_HOME");
    assertEquals(base.resolve("JAVA17_HOME"), install);
  }

  @Test
  void shouldComputeOverallProgressFractionAcrossJdkSteps() {
    assertEquals(0.0, JdkDownloadTask.computeOverallProgressFraction(0, 4, 0.0));
    assertEquals(0.25, JdkDownloadTask.computeOverallProgressFraction(0, 4, 1.0));
    assertEquals(0.625, JdkDownloadTask.computeOverallProgressFraction(2, 4, 0.5));
    assertEquals(1.0, JdkDownloadTask.computeOverallProgressFraction(4, 4, 1.0));
  }
}

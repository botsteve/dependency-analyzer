package io.botsteve.dependencyanalyzer.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DependencyDownloaderTransportRetryTest {

  @Test
  void shouldBuildMirrorCandidatesInOrder() {
    List<String> urls = DependencyDownloaderTask.buildMirrorCandidates(
        "https://primary/repo.git|https://mirror1/repo.git|https://mirror2/repo.git");
    assertEquals(3, urls.size());
    assertEquals("https://primary/repo.git", urls.get(0));
    assertEquals("https://mirror1/repo.git", urls.get(1));
    assertEquals("https://mirror2/repo.git", urls.get(2));
  }

  @Test
  void shouldClassifyCommonFailures() {
    assertEquals("AUTH_FAILURE", DependencyDownloaderTask.classifyFailureCode(new RuntimeException("401 Unauthorized"), false));
    assertEquals("DNS_FAILURE", DependencyDownloaderTask.classifyFailureCode(new RuntimeException("UnknownHostException"), false));
    assertEquals("TIMEOUT", DependencyDownloaderTask.classifyFailureCode(new RuntimeException("timed out"), false));
    assertEquals("REDIRECT_BLOCKED", DependencyDownloaderTask.classifyFailureCode(new RuntimeException("redirect blocked"), false));
    assertEquals("TAG_MISS", DependencyDownloaderTask.classifyFailureCode(new RuntimeException("tag not found"), true));
    assertEquals("NATIVE_RUNTIME",
        DependencyDownloaderTask.classifyFailureCode(
            new RuntimeException("Enumerated values of type org.eclipse.jgit.lib.CoreConfig$TrustPackedRefsStat not available"),
            true));
    assertEquals("CHECKOUT_FAILURE", DependencyDownloaderTask.classifyFailureCode(new RuntimeException("detached checkout failed"), true));
  }

  @Test
  void shouldRetryOnlyRetryableCodes() {
    assertTrue(DependencyDownloaderTask.isRetryable("TIMEOUT"));
    assertTrue(DependencyDownloaderTask.isRetryable("DNS_FAILURE"));
    assertFalse(DependencyDownloaderTask.isRetryable("AUTH_FAILURE"));
    assertFalse(DependencyDownloaderTask.isRetryable("REDIRECT_BLOCKED"));
  }

  @Test
  void shouldResolveRepoDirectoryNameFromScmUrlWhenAvailable() {
    String repoName = DependencyDownloaderTask.resolveRepoDirectoryName(
        "https://github.com/snakeyaml/snakeyaml",
        "snakeyaml");
    assertEquals("snakeyaml", repoName);
  }

  @Test
  void shouldFallbackToArtifactNameWhenScmUrlHasNoRepoSegment() {
    String repoName = DependencyDownloaderTask.resolveRepoDirectoryName("https://github.com", "snakeyaml");
    assertEquals("snakeyaml", repoName);
  }

  @Test
  void shouldAlwaysReturnNonBlankRepoDirectoryName() {
    String repoName = DependencyDownloaderTask.resolveRepoDirectoryName("", "");
    assertEquals("unknown-repo", repoName);
  }

  @Test
  void shouldResolveRepoDirectoryNameFromSourceBrowserUrl() {
    String repoName = DependencyDownloaderTask.resolveRepoDirectoryName(
        "https://bitbucket.org/asomov/snakeyaml/src/master",
        "snakeyaml");
    assertEquals("snakeyaml", repoName);
  }

  @Test
  void shouldAdvanceToNextMirrorForNonRetryableFailures() throws Exception {
    String source = Files.readString(
        Path.of("src/main/java/io/botsteve/dependencyanalyzer/tasks/DependencyDownloaderTask.java"));
    Pattern nonRetryableBranch = Pattern.compile(
        "if \\(!isRetryable\\(code\\)\\) \\{\\s+log\\.warn\\(\\\"Non-retryable download failure[\\s\\S]*?break;\\s+\\}");
    assertTrue(nonRetryableBranch.matcher(source).find());
    assertFalse(source.contains("if (!isRetryable(code)) {\n              return new DownloadResult(false,"));
  }
}

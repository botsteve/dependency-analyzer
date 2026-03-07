package io.botsteve.dependencyanalyzer.tasks;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyDownloaderTaskStatusTest {

  @Test
  void shouldSummarizeDownloadResultsWithFailedDependencyNamesAndCodes() {
    Map<String, String> failures = Map.of(
        "/tmp/downloaded_repos/project/3rd-party/snakeyaml", "FAILED:TAG_MISS [op=DL-1,dur=10ms] repo=snakeyaml",
        "/tmp/downloaded_repos/project/4th-party/commons-io/commons-lang", "FAILED:AUTH_FAILURE [op=DL-2,dur=14ms] repo=commons-lang");

    String message = DependencyDownloaderTask.buildDownloadSummaryMessage(5, failures);

    assertTrue(message.contains("Dependencies download task finished!"));
    assertTrue(message.contains("Successful: 3"));
    assertTrue(message.contains("Failed: 2"));
    assertTrue(message.contains("- snakeyaml (TAG_MISS)"));
    assertTrue(message.contains("- commons-lang (AUTH_FAILURE)"));
  }

  @Test
  void shouldSummarizeSuccessfulBatchWithoutFailedList() {
    String message = DependencyDownloaderTask.buildDownloadSummaryMessage(2, Map.of());

    assertTrue(message.contains("Successful: 2"));
    assertTrue(message.contains("Failed: 0"));
    assertFalse(message.contains("Failed dependencies:"));
  }
}

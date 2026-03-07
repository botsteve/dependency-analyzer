package io.botsteve.dependencyanalyzer.tasks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BuildRepositoriesTaskStatusTest {

  @Test
  void shouldUseStructuredStatusFormatting() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/botsteve/dependencyanalyzer/tasks/BuildRepositoriesTask.java"));
    assertTrue(source.contains("OperationStatus.success("));
    assertTrue(source.contains("OperationStatus.failure("));
  }
}

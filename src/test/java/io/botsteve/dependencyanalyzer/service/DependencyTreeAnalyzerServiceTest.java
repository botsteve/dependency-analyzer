package io.botsteve.dependencyanalyzer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class DependencyTreeAnalyzerServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void shouldExtractOnlyJsonBlockAndIgnoreTrailingMavenLogs() throws Exception {
    List<String> outputLines = List.of(
        "[INFO] Scanning for projects...",
        "[INFO] {",
        "[INFO]   \"groupId\" : \"com.example\",",
        "[INFO]   \"artifactId\" : \"demo\",",
        "[INFO]   \"version\" : \"1.0.0\",",
        "[INFO]   \"children\" : [ ]",
        "[INFO] }",
        "[INFO] ------------------------------------------------------------------------",
        "[INFO] BUILD SUCCESS"
    );

    String json = DependencyTreeAnalyzerService.extractJsonFromMavenOutput(outputLines);
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() { });

    assertEquals("demo", parsed.get("artifactId"));
  }

  @Test
  void shouldHandleBracesInsideJsonStringValues() throws Exception {
    List<String> outputLines = List.of(
        "   [INFO] [stdout] {",
        "[INFO]   \"groupId\" : \"com.example\",",
        "[INFO]   \"artifactId\" : \"demo\",",
        "[INFO]   \"classifier\" : \"note-{with-braces}\",",
        "[INFO]   \"children\" : [ ]",
        "[INFO] }",
        "[INFO] ------------------------------------------------------------------------"
    );

    String json = DependencyTreeAnalyzerService.extractJsonFromMavenOutput(outputLines);
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() { });

    assertEquals("note-{with-braces}", parsed.get("classifier"));
  }

  @Test
  void shouldIgnoreInlineNonJsonBracesBeforeActualJsonBlock() throws Exception {
    List<String> outputLines = List.of(
        "[DEBUG] Configuring mojo org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree --> {classpathElements=[A, B]}",
        "[INFO] {",
        "[INFO]   \"groupId\" : \"com.example\",",
        "[INFO]   \"artifactId\" : \"demo\",",
        "[INFO]   \"version\" : \"1.0.0\",",
        "[INFO]   \"children\" : [ ]",
        "[INFO] }"
    );

    String json = DependencyTreeAnalyzerService.extractJsonFromMavenOutput(outputLines);
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() { });

    assertEquals("demo", parsed.get("artifactId"));
  }

  @Test
  void shouldReadDependencyTreeJsonFromOutputFile() throws IOException {
    File outputFile = tempDir.resolve("dependency-tree.json").toFile();
    Files.writeString(outputFile.toPath(), "{\"groupId\":\"com.example\",\"artifactId\":\"demo\",\"version\":\"1.0.0\",\"children\":[]}");

    String json = DependencyTreeAnalyzerService.readDependencyTreeJsonFromFile(outputFile,
        List.of("[INFO] Wrote dependency tree"));

    assertTrue(json.contains("\"artifactId\":\"demo\""));
  }

  @Test
  void shouldThrowWhenDependencyTreeOutputFileIsMissing() {
    File outputFile = tempDir.resolve("missing-dependency-tree.json").toFile();

    DependencyAnalyzerException ex = assertThrows(DependencyAnalyzerException.class,
        () -> DependencyTreeAnalyzerService.readDependencyTreeJsonFromFile(outputFile,
            List.of("[INFO] BUILD SUCCESS")));

    assertTrue(ex.getMessage().contains("was not generated"));
  }

  @Test
  void shouldThrowWhenNoJsonObjectIsPresent() {
    List<String> outputLines = List.of(
        "[INFO] Scanning for projects...",
        "[INFO] ------------------------------------------------------------------------",
        "[INFO] BUILD SUCCESS"
    );

    assertThrows(DependencyAnalyzerException.class,
        () -> DependencyTreeAnalyzerService.extractJsonFromMavenOutput(outputLines));
  }
}

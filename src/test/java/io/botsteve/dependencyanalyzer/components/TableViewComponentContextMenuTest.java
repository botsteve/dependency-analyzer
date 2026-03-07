package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TableViewComponentContextMenuTest {

  @Test
  void shouldContainCopyScmCopyGavAndCopyCellMenuItems() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/botsteve/dependencyanalyzer/components/TableViewComponent.java"));
    assertTrue(source.contains("Copy SCM URL"));
    assertTrue(source.contains("Copy GAV (group:artifact:version)"));
    assertTrue(source.contains("Copy Cell Value"));
  }
}

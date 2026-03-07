package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TableViewKeyboardShortcutTest {

  @Test
  void shouldRegisterExpandCollapseAndCopyShortcuts() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/botsteve/dependencyanalyzer/components/TableViewComponent.java"));
    assertTrue(source.contains("setOnKeyPressed"));
    assertTrue(source.contains("KeyCode.E"));
    assertTrue(source.contains("KeyCode.C"));
    assertTrue(source.contains("event.isShortcutDown()"));
  }
}

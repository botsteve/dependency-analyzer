package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ColumnsComponentOutputPopupTest {

  @Test
  void shouldWireDoubleClickOutputPopupForNonEmptyCells() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/botsteve/dependencyanalyzer/components/ColumnsComponent.java"));
    assertTrue(source.contains("setOnMouseClicked"));
    assertTrue(source.contains("event.getClickCount() == 2"));
    assertTrue(source.contains("FxUtils.showTextDialog(\"Output Details\", \"Full output\", outputValue)"));
  }
}

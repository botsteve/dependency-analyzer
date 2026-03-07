package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ButtonsComponentTooltipTest {

  @Test
  void shouldConfigureTooltipsForPrimaryActions() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/botsteve/dependencyanalyzer/components/ButtonsComponent.java"));
    assertTrue(source.contains("setTooltip"));
    assertTrue(source.contains("Download selected direct 3rd-party dependencies"));
    assertTrue(source.contains("Download 4th-party dependencies for selected 3rd-party roots"));
  }
}

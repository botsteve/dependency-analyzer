package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ButtonsComponentStateGateTest {

  @Test
  void shouldBlockBuildWhenSelectionMissing() {
    assertEquals("No 3rd-party dependencies selected!", ButtonsComponent.evaluateBuildGateMessage(true, true));
  }

  @Test
  void shouldBlockBuildWhenDependenciesNotDownloaded() {
    assertEquals("No 3rd-party dependencies downloaded for current project. Run Download 3rd Party first.",
        ButtonsComponent.evaluateBuildGateMessage(false, false));
  }

  @Test
  void shouldAllowBuildWhenSelectionAndDownloadStateAreValid() {
    assertEquals("", ButtonsComponent.evaluateBuildGateMessage(false, true));
  }

  @Test
  void shouldNormalizeRequestedJdkSettingsFromExactKeys() {
    Set<String> normalized = ButtonsComponent.normalizeRequestedJdkSettings(
        Set.of("JAVA11_HOME", "JAVA8_HOME"));

    assertEquals(2, normalized.size());
    assertTrue(normalized.contains("JAVA8_HOME"));
    assertTrue(normalized.contains("JAVA11_HOME"));
  }

  @Test
  void shouldNormalizeRequestedJdkSettingsFromDecoratedPopupLabels() {
    Set<String> normalized = ButtonsComponent.normalizeRequestedJdkSettings(
        Set.of("JAVA8_HOME (missing)", "invalid path: JAVA17_HOME"));

    assertTrue(normalized.contains("JAVA8_HOME"));
    assertTrue(normalized.contains("JAVA17_HOME"));
  }
}

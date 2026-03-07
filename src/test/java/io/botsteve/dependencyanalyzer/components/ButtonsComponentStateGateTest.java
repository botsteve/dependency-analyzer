package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

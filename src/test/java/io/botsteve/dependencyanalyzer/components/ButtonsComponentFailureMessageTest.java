package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.botsteve.dependencyanalyzer.utils.LogUtils;
import javafx.concurrent.Task;
import org.junit.jupiter.api.Test;

class ButtonsComponentFailureMessageTest {

  @Test
  void shouldBuildFailureMessageWithRootCauseAndLogPath() {
    Task<Void> task = new Task<>() {
      @Override
      protected Void call() {
        return null;
      }
    };

    RuntimeException ex = new RuntimeException("Top level failure", new IllegalStateException("Root cause details"));
    String message = ButtonsComponent.buildTaskFailureMessage(task, ex);

    assertTrue(message.contains("failed: Top level failure"));
    assertTrue(message.contains("Root cause: Root cause details"));
    assertTrue(message.contains(LogUtils.getDefaultLogFilePath().toString()));
  }

  @Test
  void shouldDetectArmArchitectureFromSystemProperty() {
    String original = System.getProperty("os.arch");
    try {
      System.setProperty("os.arch", "aarch64");
      assertTrue(ButtonsComponent.isArmHostArchitecture());

      System.setProperty("os.arch", "x86_64");
      assertFalse(ButtonsComponent.isArmHostArchitecture());
    } finally {
      if (original == null) {
        System.clearProperty("os.arch");
      } else {
        System.setProperty("os.arch", original);
      }
    }
  }
}

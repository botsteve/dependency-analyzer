package io.botsteve.dependencyanalyzer.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationStatusFormattingTest {

  @Test
  void shouldFormatSuccessStatusWithOperationIdAndDuration() {
    String op = OperationStatus.createOperationId("DL");
    String status = OperationStatus.success(op, System.nanoTime(), "repo=abc");
    assertTrue(status.startsWith("SUCCESS [op=DL-"));
    assertTrue(status.contains("dur="));
    assertTrue(status.contains("repo=abc"));
  }

  @Test
  void shouldFormatFailureStatusWithCodeAndOperationId() {
    String op = OperationStatus.createOperationId("BUILD");
    String status = OperationStatus.failure("AUTH_FAILURE", op, System.nanoTime(), "repo=x");
    assertTrue(status.startsWith("FAILED:AUTH_FAILURE"));
    assertTrue(status.contains("op=BUILD-"));
  }
}

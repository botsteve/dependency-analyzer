package io.botsteve.dependencyanalyzer.utils;

import java.util.UUID;

public final class OperationStatus {

  private OperationStatus() {
  }

  public static String createOperationId(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  public static String success(String operationId, long startNanos, String details) {
    return "SUCCESS [op=" + operationId + ",dur=" + elapsedMs(startNanos) + "ms] " + safe(details);
  }

  public static String failure(String code, String operationId, long startNanos, String details) {
    return "FAILED:" + safe(code) + " [op=" + operationId + ",dur=" + elapsedMs(startNanos) + "ms] " + safe(details);
  }

  private static long elapsedMs(long startNanos) {
    return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}

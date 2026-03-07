package io.botsteve.dependencyanalyzer.exception;

public class DependencyAnalyzerException extends RuntimeException {

  public DependencyAnalyzerException(String message, Throwable cause) {
    super(message, cause);
  }

  public DependencyAnalyzerException(String message) {
    super(message);
  }

  public DependencyAnalyzerException(Throwable cause) {
    super(cause);
  }
}

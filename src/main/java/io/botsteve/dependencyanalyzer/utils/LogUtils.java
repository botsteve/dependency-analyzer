package io.botsteve.dependencyanalyzer.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for runtime log path resolution and bootstrap directory setup.
 */
public class LogUtils {

  private static final Logger LOG = LoggerFactory.getLogger(LogUtils.class);

  public static final String BASE_DIR_PROPERTY = "dependency.analyzer.base.dir";

  private LogUtils() {}

  /**
   * Initializes the base directory system property used by runtime file paths.
   *
   * @param anchorClass class used to resolve code source location when available
   */
  public static void initializeBaseDirProperty(Class<?> anchorClass) {
    String existing = System.getProperty(BASE_DIR_PROPERTY);
    if (existing != null && !existing.isBlank()) {
      ensureLogDirectory();
      return;
    }

    try {
      Path codeSource = Path.of(anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path baseDir = codeSource.toString().endsWith(".jar")
          ? codeSource.getParent()
          : Path.of(System.getProperty("user.dir"));
      if (baseDir != null) {
        System.setProperty(BASE_DIR_PROPERTY, baseDir.toAbsolutePath().toString());
        ensureLogDirectory();
        return;
      }
    } catch (Exception e) {
      LOG.warn("Could not initialize {} from anchor class {}; falling back to user.dir", BASE_DIR_PROPERTY,
          anchorClass.getName(), e);
    }

    System.setProperty(BASE_DIR_PROPERTY, Path.of(System.getProperty("user.dir")).toAbsolutePath().toString());
    ensureLogDirectory();
  }

  /**
   * @return absolute path to the runtime log directory
   */
  public static Path getLogDirectoryPath() {
    String baseDir = System.getProperty(BASE_DIR_PROPERTY, System.getProperty("user.dir"));
    return Path.of(baseDir, "logs");
  }

  /**
   * @return absolute path to the main application log file
   */
  public static Path getDefaultLogFilePath() {
    return getLogDirectoryPath().resolve("dependency-analyzer.log");
  }

  private static void ensureLogDirectory() {
    Path logDir = getLogDirectoryPath();
    try {
      if (!Files.exists(logDir)) {
        Files.createDirectories(logDir);
      }
    } catch (IOException e) {
      LOG.warn("Failed to create log directory {}", logDir, e);
    }
  }
}

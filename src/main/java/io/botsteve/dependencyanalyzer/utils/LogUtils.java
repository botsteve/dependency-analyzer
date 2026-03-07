package io.botsteve.dependencyanalyzer.utils;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogUtils {

  private static final Logger LOG = LoggerFactory.getLogger(LogUtils.class);

  public static final String BASE_DIR_PROPERTY = "dependency.analyzer.base.dir";

  private LogUtils() {}

  public static void initializeBaseDirProperty(Class<?> anchorClass) {
    String existing = System.getProperty(BASE_DIR_PROPERTY);
    if (existing != null && !existing.isBlank()) {
      return;
    }

    try {
      Path codeSource = Path.of(anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path baseDir = codeSource.toString().endsWith(".jar")
          ? codeSource.getParent()
          : Path.of(System.getProperty("user.dir"));
      if (baseDir != null) {
        System.setProperty(BASE_DIR_PROPERTY, baseDir.toAbsolutePath().toString());
        return;
      }
    } catch (Exception e) {
      LOG.warn("Could not initialize {} from anchor class {}; falling back to user.dir", BASE_DIR_PROPERTY,
          anchorClass.getName(), e);
    }

    System.setProperty(BASE_DIR_PROPERTY, Path.of(System.getProperty("user.dir")).toAbsolutePath().toString());
  }

  public static Path getLogDirectoryPath() {
    String baseDir = System.getProperty(BASE_DIR_PROPERTY, System.getProperty("user.dir"));
    return Path.of(baseDir, "logs");
  }

  public static Path getDefaultLogFilePath() {
    return getLogDirectoryPath().resolve("dependency-analyzer.log");
  }
}

package io.botsteve.dependencyanalyzer.utils;

import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScmRepositories {

  private static final Logger log = LoggerFactory.getLogger(ScmRepositories.class);

  private static final String BASE_DIR_PROPERTY = "dependency.analyzer.base.dir";
  private static final String OVERRIDES_FILE_PROPERTY = "dependency.analyzer.scm.overrides.file";
  private static final String DEFAULT_OVERRIDES_RELATIVE_PATH = "config/scm-repositories-overrides.properties";
  private static final String DEFAULT_OVERRIDES_TEMPLATE_RESOURCE = "/config/scm-repositories-overrides.properties";

  private static final String ARTIFACT_KEY_PREFIX = "artifact.";
  private static final String GROUP_KEY_PREFIX = "group.";
  private static final String LEGACY_REPLACE_DEFAULTS_KEY = "replaceDefaults";
  private static final String LEGACY_URL_PREFIX = "url.";
  private static final Object RELOAD_LOCK = new Object();

  private static volatile Path loadedOverridesPath;
  private static volatile long loadedOverridesTimestamp = Long.MIN_VALUE;
  private static volatile Map<String, String> artifactOverrides = Collections.emptyMap();
  private static volatile Map<String, String> groupOverrides = Collections.emptyMap();

  private ScmRepositories() {
  }

  /**
   * Applies SCM override rules using artifact-based mapping only.
   */
  public static String fixNonResolvableScmRepositorise(String scmUrl, String artifactId) {
    return fixNonResolvableScmRepositorise(scmUrl, null, artifactId);
  }

  /**
   * Ensures the external SCM override file exists and is initialized from template when missing.
   */
  public static void initializeOverridesFile() {
    synchronized (RELOAD_LOCK) {
      Path overridesPath = resolveOverridesPath();
      ensureOverridesFileExists(overridesPath);
    }
  }

  /**
   * Applies SCM override mapping for artifact/group keys and returns canonical SCM URL.
   */
  public static String fixNonResolvableScmRepositorise(String scmUrl, String groupId, String artifactId) {
    reloadOverridesIfNeeded();

    String normalizedScmUrl = ScmUrlUtils.normalizeForMatching(scmUrl);
    String normalizedArtifactId = normalizeLookupKey(artifactId);
    String normalizedGroupId = normalizeLookupKey(groupId);

    String artifactMatch = findExactOrPrefix(artifactOverrides, normalizedArtifactId);
    if (artifactMatch != null) {
      log.info("SCM override match (artifact): groupId='{}', artifactId='{}' => {}",
          safe(groupId), safe(artifactId), artifactMatch);
      return ScmUrlUtils.canonicalize(artifactMatch);
    }

    String groupMatch = findExactOrPrefix(groupOverrides, normalizedGroupId);
    if (groupMatch != null) {
      log.info("SCM override match (group): groupId='{}', artifactId='{}' => {}",
          safe(groupId), safe(artifactId), groupMatch);
      return ScmUrlUtils.canonicalize(groupMatch);
    }

    return normalizedScmUrl;
  }

  private static String findExactOrPrefix(Map<String, String> mapping, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
      if (mapping.containsKey(value)) {
        return mapping.get(value);
      }

    String bestMatch = null;
    int bestLength = -1;
    for (Map.Entry<String, String> entry : mapping.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      if (value.startsWith(key) && key.length() > bestLength) {
        bestLength = key.length();
        bestMatch = entry.getValue();
      }
    }
    return bestMatch;
  }

  private static void reloadOverridesIfNeeded() {
    Path overridesPath = resolveOverridesPath();

    synchronized (RELOAD_LOCK) {
      ensureOverridesFileExists(overridesPath);
      long lastModified = getLastModified(overridesPath);

      if (overridesPath.equals(loadedOverridesPath) && loadedOverridesTimestamp == lastModified) {
        return;
      }
      loadOverridesFromFile(overridesPath, lastModified);
    }
  }

  private static Path resolveOverridesPath() {
    String explicitPath = System.getProperty(OVERRIDES_FILE_PROPERTY, "").trim();
    if (!explicitPath.isEmpty()) {
      return Path.of(explicitPath).toAbsolutePath().normalize();
    }

    String baseDir = System.getProperty(BASE_DIR_PROPERTY, System.getProperty("user.dir"));
    return Path.of(baseDir).resolve(DEFAULT_OVERRIDES_RELATIVE_PATH).toAbsolutePath().normalize();
  }

  private static void ensureOverridesFileExists(Path path) {
    if (Files.exists(path)) {
      return;
    }

    try {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to prepare SCM overrides directory for " + path, e);
    }

    try (InputStream templateStream = ScmRepositories.class.getResourceAsStream(DEFAULT_OVERRIDES_TEMPLATE_RESOURCE)) {
      if (templateStream == null) {
        throw new DependencyAnalyzerException("SCM overrides template resource not found: " + DEFAULT_OVERRIDES_TEMPLATE_RESOURCE);
      }
      Files.copy(templateStream, path);
      log.info("Created SCM overrides file from classpath template at {}", path);
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to create SCM overrides file at " + path, e);
    }
  }

  private static long getLastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to read SCM overrides timestamp: " + path, e);
    }
  }

  private static void loadOverridesFromFile(Path path, long timestamp) {
    LoadedOverrides loadedOverrides = readAndValidateOverrides(path);

    artifactOverrides = Collections.unmodifiableMap(loadedOverrides.artifactOverrides());
    groupOverrides = Collections.unmodifiableMap(loadedOverrides.groupOverrides());
    loadedOverridesPath = path;
    loadedOverridesTimestamp = timestamp;

    log.info("Loaded SCM overrides from {} (artifact={}, group={})",
        path, artifactOverrides.size(), groupOverrides.size());
  }

  private static LoadedOverrides readAndValidateOverrides(Path path) {
    List<String> lines;
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      lines = reader.lines().toList();
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to load SCM overrides from " + path + ": " + e.getMessage(), e);
    }

    Map<String, String> loadedArtifactOverrides = new HashMap<>();
    Map<String, String> loadedGroupOverrides = new HashMap<>();

    for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
      String originalLine = lines.get(lineNumber - 1);
      String trimmedLine = originalLine == null ? "" : originalLine.trim();
      if (trimmedLine.isEmpty() || trimmedLine.startsWith("#") || trimmedLine.startsWith("!")) {
        continue;
      }

      int assignmentIndex = originalLine.indexOf('=');
      if (assignmentIndex < 0) {
        throw new DependencyAnalyzerException("Invalid SCM override entry at " + path + ":" + lineNumber
            + " (expected '<key>=<repoUrl>')");
      }

      String key = originalLine.substring(0, assignmentIndex).trim();
      if (key.isEmpty()) {
        throw new DependencyAnalyzerException("Invalid blank SCM override key at " + path + ":" + lineNumber);
      }

      String value = originalLine.substring(assignmentIndex + 1).trim();
      if (value.isEmpty()) {
        throw new DependencyAnalyzerException("Invalid SCM override for key '" + key + "': value is blank");
      }
      if (!isSupportedOverrideTarget(value)) {
        throw new DependencyAnalyzerException("Invalid SCM override target for key '" + key
            + "'. Supported targets must start with https://, http://, ssh:// or git@");
      }

      if (LEGACY_REPLACE_DEFAULTS_KEY.equals(key)) {
        throw new DependencyAnalyzerException("Unsupported SCM override key 'replaceDefaults'. "
            + "Use external artifact/group mappings only.");
      }

      if (key.startsWith(LEGACY_URL_PREFIX)) {
        throw new DependencyAnalyzerException("Unsupported SCM override key prefix 'url.'. "
            + "Use artifact or group mappings instead.");
      }

      if (key.startsWith(ARTIFACT_KEY_PREFIX)) {
        String artifactKey = key.substring(ARTIFACT_KEY_PREFIX.length()).trim();
        if (artifactKey.isEmpty()) {
          throw new DependencyAnalyzerException("Invalid SCM override key '" + key + "': missing artifact id");
        }
        loadedArtifactOverrides.put(normalizeLookupKey(artifactKey), ScmUrlUtils.canonicalize(value));
        continue;
      }

      if (key.startsWith(GROUP_KEY_PREFIX)) {
        String groupKey = key.substring(GROUP_KEY_PREFIX.length()).trim();
        if (groupKey.isEmpty()) {
          throw new DependencyAnalyzerException("Invalid SCM override key '" + key + "': missing group id");
        }
        loadedGroupOverrides.put(normalizeLookupKey(groupKey), ScmUrlUtils.canonicalize(value));
        continue;
      }

      loadedArtifactOverrides.put(normalizeLookupKey(key), ScmUrlUtils.canonicalize(value));
    }

    if (loadedArtifactOverrides.isEmpty() && loadedGroupOverrides.isEmpty()) {
      throw new DependencyAnalyzerException("No SCM overrides configured in " + path);
    }

    return new LoadedOverrides(loadedArtifactOverrides, loadedGroupOverrides);
  }

  private static boolean isSupportedOverrideTarget(String value) {
    return value.startsWith("https://")
        || value.startsWith("http://")
        || value.startsWith("ssh://")
        || value.startsWith("git@");
  }

  private static String normalizeLookupKey(String key) {
    if (key == null) {
      return "";
    }
    return key.trim().toLowerCase(Locale.ROOT);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  static void resetForTests() {
    synchronized (RELOAD_LOCK) {
      loadedOverridesPath = null;
      loadedOverridesTimestamp = Long.MIN_VALUE;
      artifactOverrides = Collections.emptyMap();
      groupOverrides = Collections.emptyMap();
    }
  }

  private record LoadedOverrides(Map<String, String> artifactOverrides, Map<String, String> groupOverrides) {
  }
}

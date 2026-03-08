package io.botsteve.dependencyanalyzer.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScmUrlUtils {

  private static final Logger LOG = LoggerFactory.getLogger(ScmUrlUtils.class);
  private static final String SCM_URL_NOT_FOUND = "SCM URL not found";
  private static final Set<String> REPO_NAVIGATION_SEGMENTS = Set.of(
      "src", "tree", "blob", "browse", "trunk", "branches", "branch",
      "tags", "tag", "commits", "commit", "raw", "history", "files", "file", "-");

  private ScmUrlUtils() {
  }

  /**
   * Normalizes SCM URL variants into canonical HTTPS-like repository URLs.
   *
   * @param rawScmUrl raw SCM value from metadata
   * @return canonical URL (or empty string when input is blank)
   */
  public static String canonicalize(String rawScmUrl) {
    if (rawScmUrl == null) {
      return "";
    }

    String url = rawScmUrl.trim();
    if (url.isEmpty()) {
      return "";
    }
    if (SCM_URL_NOT_FOUND.equals(url)) {
      return SCM_URL_NOT_FOUND;
    }

    if (url.startsWith("scm:git:")) {
      url = url.substring("scm:git:".length());
    }

    if (url.startsWith("git://")) {
      url = "https://" + url.substring("git://".length());
    }

    if (url.startsWith("git@")) {
      url = toHttpsFromScpStyle(url);
    }

    try {
      URI uri = new URI(url);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return trimGitAndTrailingSlash(url);
      }

      String scheme = normalizeScheme(uri.getScheme());
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      String path = trimPathAfterGitSuffix(uri.getPath() == null ? "" : uri.getPath());
      String query = uri.getQuery();

      String rebuilt = scheme + "://" + host + (port > 0 ? ":" + port : "") + path + (query == null ? "" : "?" + query);
      return trimGitAndTrailingSlash(rebuilt);
    } catch (URISyntaxException e) {
      LOG.debug("Could not parse SCM URL '{}'; applying fallback normalization (reason: {})",
          url, e.getMessage());
      return trimGitAndTrailingSlash(url);
    }
  }

  /**
   * Produces a matching-safe normalized representation used in URL comparisons.
   */
  public static String normalizeForMatching(String rawScmUrl) {
    return canonicalize(rawScmUrl);
  }

  /**
   * Resolves repository directory name from SCM URL with artifact fallback.
   */
  public static String resolveRepoName(String scmUrl, String artifactFallback) {
    String canonical = canonicalize(scmUrl);
    String queryRepo = extractRepoNameFromQuery(canonical);
    if (!queryRepo.isBlank()) {
      return sanitizePathSegment(queryRepo);
    }

    String pathRepo = extractRepoNameFromPath(canonical);
    if (!pathRepo.isBlank()) {
      return sanitizePathSegment(pathRepo);
    }

    return sanitizePathSegment(artifactFallback == null ? "" : artifactFallback);
  }

  /**
   * Sanitizes a value so it can be safely used as a filesystem path segment.
   */
  public static String sanitizePathSegment(String rawValue) {
    if (rawValue == null) {
      return "";
    }
    String sanitized = rawValue.trim()
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
        .replace('?', '_')
        .replace('&', '_')
        .replace('=', '_');
    return sanitized;
  }

  /**
   * Builds a normalized repository key from target directory and repository name.
   */
  public static String toRepoKey(String targetDirectory, String repoName) {
    if (repoName == null || repoName.isBlank()) {
      return "";
    }
    if (targetDirectory == null || targetDirectory.isBlank()) {
      return repoName;
    }
    return Path.of(targetDirectory, repoName).normalize().toString();
  }

  private static String extractRepoNameFromQuery(String url) {
    int queryIndex = url.indexOf('?');
    if (queryIndex < 0 || queryIndex + 1 >= url.length()) {
      return "";
    }

    String query = url.substring(queryIndex + 1);
    String[] parts = query.split("&");
    for (String part : parts) {
      String[] kv = part.split("=", 2);
      if (kv.length == 2 && "p".equals(kv[0])) {
        return trimGitAndTrailingSlash(kv[1]);
      }
    }
    return "";
  }

  private static String extractRepoNameFromPath(String url) {
    String withoutQuery = url;
    int queryIndex = withoutQuery.indexOf('?');
    if (queryIndex >= 0) {
      withoutQuery = withoutQuery.substring(0, queryIndex);
    }

    URI uri = null;
    try {
      uri = new URI(withoutQuery);
    } catch (URISyntaxException e) {
      uri = null;
    }

    if (uri != null) {
      String path = uri.getPath();
      if (uri.getHost() != null && (path == null || path.isBlank() || "/".equals(path))) {
        return "";
      }
      String extracted = extractRepoNameFromPathSegments(path);
      if (!extracted.isBlank()) {
        return extracted;
      }
    }

    String extracted = extractRepoNameFromPathSegments(withoutQuery);
    if (!extracted.isBlank()) {
      return extracted;
    }
    int slash = withoutQuery.lastIndexOf('/');
    if (slash < 0 || slash + 1 >= withoutQuery.length()) {
      return "";
    }
    return trimGitAndTrailingSlash(withoutQuery.substring(slash + 1));
  }

  private static String extractRepoNameFromPathSegments(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }

    String[] rawSegments = path.split("/");
    List<String> segments = new ArrayList<>();
    for (String rawSegment : rawSegments) {
      String segment = trimGitAndTrailingSlash(rawSegment);
      if (!segment.isBlank()) {
        segments.add(segment);
      }
    }
    if (segments.isEmpty()) {
      return "";
    }

    for (int i = 0; i < segments.size(); i++) {
      String segment = segments.get(i).toLowerCase(Locale.ROOT);
      if ("-".equals(segment) && i + 1 < segments.size()) {
        String next = segments.get(i + 1).toLowerCase(Locale.ROOT);
        if (REPO_NAVIGATION_SEGMENTS.contains(next) && i > 0) {
          return trimGitAndTrailingSlash(segments.get(i - 1));
        }
      }
      if (REPO_NAVIGATION_SEGMENTS.contains(segment) && i > 0) {
        return trimGitAndTrailingSlash(segments.get(i - 1));
      }
    }

    return trimGitAndTrailingSlash(segments.get(segments.size() - 1));
  }

  private static String trimGitAndTrailingSlash(String value) {
    String out = trimPathAfterGitSuffix(value == null ? "" : value.trim());
    while (out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    if (out.endsWith(".git")) {
      out = out.substring(0, out.length() - 4);
    }
    return out;
  }

  private static String trimPathAfterGitSuffix(String value) {
    if (value == null || value.isBlank()) {
      return value == null ? "" : value;
    }

    String lower = value.toLowerCase(Locale.ROOT);
    int slashIndex = lower.indexOf(".git/");
    if (slashIndex >= 0) {
      return value.substring(0, slashIndex + 4);
    }

    int backslashIndex = lower.indexOf(".git\\");
    if (backslashIndex >= 0) {
      return value.substring(0, backslashIndex + 4);
    }

    return value;
  }

  private static String toHttpsFromScpStyle(String scpLike) {
    int atIndex = scpLike.indexOf('@');
    int colonIndex = scpLike.indexOf(':', atIndex + 1);
    if (atIndex < 0 || colonIndex < 0) {
      return scpLike;
    }
    String host = scpLike.substring(atIndex + 1, colonIndex);
    String path = scpLike.substring(colonIndex + 1);
    return "https://" + host + "/" + path;
  }

  private static String normalizeScheme(String scheme) {
    if (scheme == null) {
      return "https";
    }
    String lower = scheme.toLowerCase(Locale.ROOT);
    if ("ssh".equals(lower) || "git".equals(lower)) {
      return "https";
    }
    return lower;
  }
}

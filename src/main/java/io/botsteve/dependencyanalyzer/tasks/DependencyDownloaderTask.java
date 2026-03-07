package io.botsteve.dependencyanalyzer.tasks;

import static io.botsteve.dependencyanalyzer.tasks.CheckoutTagsTask.checkoutTag;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.getErrorAlertAndCloseProgressBar;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showAlert;
import static io.botsteve.dependencyanalyzer.utils.ProxyUtil.configureProxyIfEnvAvailable;
import static io.botsteve.dependencyanalyzer.utils.Utils.getRepositoriesPath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import io.botsteve.dependencyanalyzer.utils.ForceDeleteUtil;
import io.botsteve.dependencyanalyzer.utils.OperationStatus;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads selected dependency repositories and resolves checkout tags.
 *
 * <p>The task supports retry/mirror logic, structured status reporting, and native-safe git CLI
 * paths for clone/fetch/checkout operations.</p>
 */
public class DependencyDownloaderTask extends Task<Map<String, String>> {

  private static final Logger log = LoggerFactory.getLogger(DependencyDownloaderTask.class);
  private static final int MAX_RETRIES_PER_URL = 2;
  private static final long[] RETRY_BACKOFF_MS = {500L, 1500L};
  private static final int MAX_HTTP_REDIRECTS = 5;
  private static final long GIT_COMMAND_TIMEOUT_SECONDS = 300L;
  private static final TransportConfigCallback TRANSPORT_CONFIG_CALLBACK = new NoOpTransportConfigCallback();

  private final List<DownloadRequest> downloadRequests;
  private final ProgressBar progressBar;
  private final Label progressLabel;
  private final boolean cleanUp;
  private final String projectName;
  private final ConcurrentHashMap<String, String> repoToCheckoutTag = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> failedDownloads = new ConcurrentHashMap<>();

  /**
   * Immutable download request descriptor for one dependency repository.
   *
   * @param scmUrl repository SCM URL (or mirror chain)
   * @param version dependency version used for tag matching
   * @param targetDirectory local target directory for clone/fetch
   * @param fallbackRepoName artifact-based fallback name when URL parsing is insufficient
   */
  public record DownloadRequest(String scmUrl, String version, String targetDirectory, String fallbackRepoName) {

    /**
     * Convenience overload that uses an empty fallback repository name.
     *
     * @param scmUrl repository SCM URL (or mirror chain)
     * @param version dependency version used for tag matching
     * @param targetDirectory local target directory for clone/fetch
     */
    public DownloadRequest(String scmUrl, String version, String targetDirectory) {
      this(scmUrl, version, targetDirectory, "");
    }
  }

  /**
   * Creates a downloader task using default repository target directories.
   */
  public DependencyDownloaderTask(Map<String, String> scmToVersionRepos,
                                  ProgressBar progressBar,
                                  Label progressLabel,
                                  boolean cleanUp,
                                  String projectName) {
    this(buildRequests(scmToVersionRepos, Collections.emptyMap(), Collections.emptyMap(), projectName),
        progressBar, progressLabel, cleanUp, projectName);
  }

  /**
   * Creates a downloader task with explicit target directories per SCM URL.
   */
  public DependencyDownloaderTask(Map<String, String> scmToVersionRepos,
                                  ProgressBar progressBar,
                                  Label progressLabel,
                                  boolean cleanUp,
                                  String projectName,
                                  Map<String, String> scmToTargetDirectories) {
    this(buildRequests(scmToVersionRepos, scmToTargetDirectories, Collections.emptyMap(), projectName),
        progressBar, progressLabel, cleanUp, projectName);
  }

  /**
   * Creates a downloader task with explicit target directories and fallback repository names.
   */
  public DependencyDownloaderTask(Map<String, String> scmToVersionRepos,
                                  ProgressBar progressBar,
                                  Label progressLabel,
                                  boolean cleanUp,
                                  String projectName,
                                  Map<String, String> scmToTargetDirectories,
                                  Map<String, String> scmToFallbackRepoNames) {
    this(buildRequests(scmToVersionRepos, scmToTargetDirectories, scmToFallbackRepoNames, projectName),
        progressBar, progressLabel, cleanUp, projectName);
  }

  /**
   * Creates a downloader task from pre-built immutable download requests.
   */
  public DependencyDownloaderTask(List<DownloadRequest> downloadRequests,
                                  ProgressBar progressBar,
                                  Label progressLabel,
                                  boolean cleanUp,
                                  String projectName) {
    this.downloadRequests = downloadRequests == null ? List.of() : List.copyOf(downloadRequests);
    this.progressBar = progressBar;
    this.progressLabel = progressLabel;
    this.cleanUp = cleanUp;
    this.projectName = projectName;
  }

  private static List<DownloadRequest> buildRequests(Map<String, String> scmToVersionRepos,
                                                     Map<String, String> scmToTargetDirectories,
                                                     Map<String, String> scmToFallbackRepoNames,
                                                     String projectName) {
    if (scmToVersionRepos == null || scmToVersionRepos.isEmpty()) {
      return List.of();
    }

    List<DownloadRequest> requests = new ArrayList<>();
    for (Map.Entry<String, String> entry : scmToVersionRepos.entrySet()) {
      String scmUrl = entry.getKey();
      if (scmUrl == null || scmUrl.isBlank()) {
        continue;
      }
      String targetDirectory = scmToTargetDirectories == null
          ? getRepositoriesPath(projectName)
          : scmToTargetDirectories.getOrDefault(scmUrl, getRepositoriesPath(projectName));
      String fallbackRepoName = scmToFallbackRepoNames == null
          ? ""
          : scmToFallbackRepoNames.getOrDefault(scmUrl, "");
      requests.add(new DownloadRequest(scmUrl, entry.getValue(), targetDirectory, fallbackRepoName));
    }
    return requests;
  }


  @Override
  protected void failed() {
    super.failed();
    Throwable exception = getException();
    if (exception != null) {
      log.error(exception.getMessage(), exception);
      String details = formatFailedDependencies(failedDownloads);
      String message = "Failed to download dependencies. Check logs for details: " + exception.getMessage();
      if (!details.isBlank()) {
        message = message + "\n\n" + details;
      }
      getErrorAlertAndCloseProgressBar(message, progressBar, progressLabel);
    }
  }

  @Override
  protected void succeeded() {
    showAlert(buildDownloadSummaryMessage(downloadRequests.size(), failedDownloads));
    progressBar.setVisible(false);
    progressLabel.setVisible(false);
  }

  /**
   * Executes cleanup, download, and checkout flow for all requested repositories.
   *
   * @return map of repository keys to checkout tags or failure status strings
   */
  @Override
  protected Map<String, String> call() {
    updateMessage("Cleaning up previous downloaded repositories");
    repoToCheckoutTag.clear();
    failedDownloads.clear();
    if (cleanUp) {
        try {
            cleanUpDownloadedDependencies();
        } catch (Exception e) {
            log.error("Cleanup failed", e);
            throw new RuntimeException("Failed to clean up repositories", e);
        }
    }

    Map<String, String> failures = new ConcurrentHashMap<>();

    downloadRequests.parallelStream().forEach(request -> {
      String scmUrl = request.scmUrl();
      String resolvedRepoName = resolveRepoDirectoryName(scmUrl, request.fallbackRepoName());
      String repoName = resolvedRepoName;
      String repoKey = buildRepoKey(request.targetDirectory(), repoName);
      String operationId = OperationStatus.createOperationId("DL");
      long startNanos = System.nanoTime();

      try {
        updateMessage("Downloading: " + repoName);

        File localRepoDir = new File(request.targetDirectory(), repoName);
        ensureParentDirectory(localRepoDir);
        configureProxyIfEnvAvailable();
        DownloadResult result = downloadWithMirrorAndRetry(request, localRepoDir, repoName);
        if (!result.success()) {
          failures.put(repoKey, result.message());
          failedDownloads.put(repoKey, result.message());
          repoToCheckoutTag.put(repoKey, result.message());
          return;
        }

        String checkoutTagStr;
        try {
          checkoutTagStr = checkoutTagWithFallback(localRepoDir, request.version(), scmUrl);
        } catch (Exception checkoutException) {
          String details = "repo=" + repoName + " url=" + scmUrl + " message=" + Objects.toString(checkoutException.getMessage(), "Unknown error");
          String status = OperationStatus.failure(classifyFailureCode(checkoutException, true), operationId, startNanos, details);
          failures.put(repoKey, status);
          failedDownloads.put(repoKey, status);
          repoToCheckoutTag.put(repoKey, status);
          return;
        }
        repoToCheckoutTag.put(repoKey, checkoutTagStr.replace("refs/tags/", ""));

      } catch (Exception e) {
        log.error("Failed to clone/checkout repository: {}", scmUrl, e);
        String code = classifyFailureCode(e, false);
        String details = "repo=" + repoName + " url=" + scmUrl + " message=" + (e.getMessage() == null ? "Unknown error" : e.getMessage());
        String status = OperationStatus.failure(code, operationId, startNanos, details);
        failures.put(repoKey, status);
        failedDownloads.put(repoKey, status);
        repoToCheckoutTag.put(repoKey, status);
      }
    });

    if (!downloadRequests.isEmpty() && failures.size() == downloadRequests.size()) {
      String details = formatFailedDependencies(failures);
      String message = "Failed to download all selected dependencies. Check logs for details.";
      if (!details.isBlank()) {
        message = message + "\n\n" + details;
      }
      throw new DependencyAnalyzerException(message);
    }

    return repoToCheckoutTag;
  }

  private void ensureParentDirectory(File localRepoDir) {
    File parent = localRepoDir.getParentFile();
    if (parent == null || parent.exists()) {
      return;
    }
    if (!parent.mkdirs() && !parent.exists()) {
      throw new DependencyAnalyzerException("Failed to create target directory: " + parent.getAbsolutePath());
    }
  }

  private static String buildRepoKey(String targetDirectory, String repoName) {
    return ScmUrlUtils.toRepoKey(targetDirectory, repoName);
  }

  private void cleanUpDownloadedDependencies() throws IOException {
    Path dir = Paths.get(getRepositoriesPath(projectName));
    if (!Files.exists(dir)) return;

    try (Stream<Path> paths = Files.list(dir)) {
      paths.filter(Files::isDirectory)
          .forEach(path -> {
            try {
              ForceDeleteUtil.forceDeleteDirectory(path);
              log.debug("Deleted directory: {}", path);
            } catch (IOException e) {
              log.error("Failed to delete directory: {}", path, e);
              throw new RuntimeException(
                  "Cleanup process stopped due to error. If this persists try to delete the downloaded_repos directory manually",
                  e);
            }
          });
    }
  }

  private DownloadResult downloadWithMirrorAndRetry(DownloadRequest request, File localRepoDir, String repoName) {
    List<String> candidateUrls = buildMirrorCandidates(request.scmUrl());
    String operationId = OperationStatus.createOperationId("DL");
    long startNanos = System.nanoTime();
    Exception lastFailure = null;

    for (String candidateUrl : candidateUrls) {
      int attempt = 0;
      while (attempt <= MAX_RETRIES_PER_URL) {
        try {
          cloneOrFetchRepository(candidateUrl, localRepoDir);
          String details = "repo=" + repoName + " url=" + candidateUrl + " attempt=" + (attempt + 1);
          return new DownloadResult(true, OperationStatus.success(operationId, startNanos, details));
        } catch (Exception e) {
          lastFailure = e;
          String code = classifyFailureCode(e, false);
          if (!isRetryable(code) || attempt >= MAX_RETRIES_PER_URL) {
            if (!isRetryable(code)) {
              log.warn("Non-retryable download failure for {} on {} (attempt {}): {}",
                  repoName,
                  candidateUrl,
                  attempt + 1,
                  e.getMessage());
              break;
            }
            break;
          }
          sleepBackoff(attempt);
          attempt++;
        }
      }
    }

    String code = classifyFailureCode(lastFailure, false);
    String details = "repo=" + repoName + " message=" + (lastFailure == null ? "Unknown error" : Objects.toString(lastFailure.getMessage(), "Unknown error"));
    return new DownloadResult(false, OperationStatus.failure(code, operationId, startNanos, details));
  }

  /**
   * Performs clone/fetch using native-aware strategy:
   * JGit on JVM and CLI git on native runtime.
   */
  private void cloneOrFetchRepository(String scmUrl, File localRepoDir) throws Exception {
    if (shouldTryCliGitFallback()) {
      cloneOrFetchWithGitCli(scmUrl, localRepoDir);
      return;
    }

    boolean repoReady = false;
    if (!cleanUp && localRepoDir.exists() && new File(localRepoDir, ".git").exists()) {
      try (Git git = Git.open(localRepoDir)) {
        log.info("Updating existing repository: {}", scmUrl);
        git.fetch().setTagOpt(TagOpt.FETCH_TAGS).setTransportConfigCallback(TRANSPORT_CONFIG_CALLBACK).call();
        repoReady = true;
      } catch (Exception e) {
        if (shouldTryCliGitFallback()) {
          log.warn("JGit fetch failed for {}. Falling back to git CLI: {}", localRepoDir, e.getMessage());
          runGitCommand(localRepoDir.getParentFile(), "git", "-C", localRepoDir.getAbsolutePath(), "fetch", "--tags", "--prune");
          repoReady = true;
        } else {
          log.warn("Failed to reuse repository {}: {}", localRepoDir, e.getMessage());
          ForceDeleteUtil.forceDeleteDirectory(localRepoDir.toPath());
        }
      }
    } else if (localRepoDir.exists()) {
      ForceDeleteUtil.forceDeleteDirectory(localRepoDir.toPath());
    }

    if (!repoReady) {
      try {
        try (Git git = Git.cloneRepository()
            .setURI(scmUrl)
            .setDirectory(localRepoDir)
            .setCloneAllBranches(true)
            .setDepth(1)
            .setTransportConfigCallback(TRANSPORT_CONFIG_CALLBACK)
            .call()) {
          log.info("Repository cloned successfully: {}", scmUrl);
        }
      } catch (Exception cloneException) {
        if (!shouldTryCliGitFallback()) {
          throw cloneException;
        }
        log.warn("JGit clone failed for {}. Falling back to git CLI: {}", scmUrl, cloneException.getMessage());
        if (localRepoDir.exists()) {
          ForceDeleteUtil.forceDeleteDirectory(localRepoDir.toPath());
        }
        runGitCommand(localRepoDir.getParentFile(),
            "git", "clone", "--depth", "1", "--no-single-branch", "--tags", scmUrl, localRepoDir.getAbsolutePath());
      }
    }
  }

  private void cloneOrFetchWithGitCli(String scmUrl, File localRepoDir) throws Exception {
    if (!cleanUp && localRepoDir.exists() && new File(localRepoDir, ".git").exists()) {
      runGitCommand(localRepoDir, "git", "fetch", "--tags", "--prune");
      return;
    }
    if (localRepoDir.exists()) {
      ForceDeleteUtil.forceDeleteDirectory(localRepoDir.toPath());
    }
    runGitCommand(localRepoDir.getParentFile(),
        "git", "clone", "--depth", "1", "--no-single-branch", "--tags", scmUrl, localRepoDir.getAbsolutePath());
  }

  /**
   * Resolves and checks out a tag using native-safe fallback rules.
   */
  private String checkoutTagWithFallback(File localRepoDir, String version, String scmUrl) throws Exception {
    if (shouldTryCliGitFallback()) {
      return checkoutTagWithGitCli(localRepoDir, version);
    }
    try {
      return checkoutTag(localRepoDir, version);
    } catch (Exception jgitCheckoutException) {
      if (!shouldTryCliGitFallback()) {
        throw jgitCheckoutException;
      }
      log.warn("JGit checkout failed for {}. Falling back to git CLI: {}", scmUrl, jgitCheckoutException.getMessage());
      return checkoutTagWithGitCli(localRepoDir, version);
    }
  }

  private String checkoutTagWithGitCli(File localRepoDir, String version) throws Exception {
    List<String> matchingTags = findMatchingTagsWithGitCli(localRepoDir, version);
    if (matchingTags.isEmpty()) {
      runGitCommand(localRepoDir, "git", "fetch", "--tags", "--prune");
      matchingTags = findMatchingTagsWithGitCli(localRepoDir, version);
    }
    if (matchingTags.isEmpty()) {
      throw new DependencyAnalyzerException(
          String.format("No tags found matching the pattern for repo %s with version %s", localRepoDir.getName(), version));
    }
    String tagName = matchingTags.getFirst();
    runGitCommand(localRepoDir, "git", "checkout", "--force", "--detach", "refs/tags/" + tagName);
    return "refs/tags/" + tagName;
  }

  private List<String> findMatchingTagsWithGitCli(File localRepoDir, String version) throws Exception {
    String output = runGitCommand(localRepoDir,
        "git",
        "for-each-ref",
        "--sort=-creatordate",
        "--format=%(refname:short)",
        "refs/tags");
    if (output.isBlank()) {
      return List.of();
    }
    return output.lines()
        .map(String::trim)
        .filter(tag -> !tag.isBlank())
        .filter(tag -> CheckoutTagsTask.versionsMatch(version, tag))
        .toList();
  }

  private static boolean shouldTryCliGitFallback() {
    return isNativeRuntime();
  }

  static boolean isNativeRuntime() {
    try {
      Class<?> imageInfo = Class.forName("org.graalvm.nativeimage.ImageInfo");
      Object result = imageInfo.getMethod("inImageRuntimeCode").invoke(null);
      return result instanceof Boolean && (Boolean) result;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String runGitCommand(File workingDirectory, String... command) throws Exception {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      processBuilder.directory(workingDirectory);
    }
    processBuilder.redirectErrorStream(true);
    Process process = processBuilder.start();

    CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
      try (InputStream inputStream = process.getInputStream()) {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        return "";
      }
    });

    boolean finished;
    try {
      finished = process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new DependencyAnalyzerException("Interrupted while waiting for git command: " + String.join(" ", command), e);
    }

    if (!finished) {
      process.destroyForcibly();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      String timeoutOutput;
      try {
        timeoutOutput = outputFuture.get(2, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        timeoutOutput = "";
      }
      throw new DependencyAnalyzerException(
          "Git command timed out: " + String.join(" ", command) + "\n" + trimOutput(timeoutOutput));
    }

    String output;
    try {
      output = outputFuture.get(2, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      output = "";
    }

    if (process.exitValue() != 0) {
      throw new DependencyAnalyzerException(
          "Git command failed (exit=" + process.exitValue() + "): " + String.join(" ", command)
              + "\n" + trimOutput(output));
    }
    return output;
  }

  private static String trimOutput(String output) {
    if (output == null) {
      return "";
    }
    String normalized = output.trim();
    if (normalized.length() <= 600) {
      return normalized;
    }
    return normalized.substring(0, 600) + "...";
  }

  /**
   * Splits mirror chains into clone candidates while preserving original order.
   *
   * @param rawScmUrl single URL or pipe-separated mirror chain
   * @return ordered clone candidates
   */
  static List<String> buildMirrorCandidates(String rawScmUrl) {
    if (rawScmUrl == null || rawScmUrl.isBlank()) {
      return List.of();
    }
    if (!rawScmUrl.contains("|")) {
      return List.of(rawScmUrl);
    }
    List<String> urls = new ArrayList<>();
    for (String part : rawScmUrl.split("\\|")) {
      String candidate = part == null ? "" : part.trim();
      if (!candidate.isBlank()) {
        urls.add(candidate);
      }
    }
    return urls.isEmpty() ? List.of(rawScmUrl) : urls;
  }

  private static void sleepBackoff(int attempt) {
    int index = Math.max(0, Math.min(attempt, RETRY_BACKOFF_MS.length - 1));
    try {
      Thread.sleep(RETRY_BACKOFF_MS[index]);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Classifies a failure into operational status taxonomy used in UI and logs.
   *
   * @param throwable root failure
   * @param checkoutPhase whether the failure happened during checkout/tag resolution
   * @return normalized failure code
   */
  static String classifyFailureCode(Throwable throwable, boolean checkoutPhase) {
    if (throwable == null) {
      return checkoutPhase ? "CHECKOUT_FAILURE" : "NETWORK_FAILURE";
    }
    String message = Objects.toString(throwable.getMessage(), "").toLowerCase();
    if (message.contains("no tags found") || (message.contains("tag") && message.contains("not"))) {
      return "TAG_MISS";
    }
    if (message.contains("enumerated values of type") || message.contains("not available")) {
      return "NATIVE_RUNTIME";
    }
    if (message.contains("auth") || message.contains("401") || message.contains("403") || message.contains("not authorized")) {
      return "AUTH_FAILURE";
    }
    if (message.contains("unknownhost") || message.contains("name or service") || message.contains("dns")) {
      return "DNS_FAILURE";
    }
    if (message.contains("timeout") || message.contains("timed out")) {
      return "TIMEOUT";
    }
    if (message.contains("redirect")) {
      return "REDIRECT_BLOCKED";
    }
    if (checkoutPhase) {
      return "CHECKOUT_FAILURE";
    }
    return "NETWORK_FAILURE";
  }

  /**
   * Returns whether a failure code should trigger retry on the same mirror URL.
   */
  static boolean isRetryable(String code) {
    return "NETWORK_FAILURE".equals(code) || "TIMEOUT".equals(code) || "DNS_FAILURE".equals(code);
  }

  /**
   * Resolves a stable repository directory name from SCM URL and fallback artifact name.
   */
  static String resolveRepoDirectoryName(String scmUrl, String fallbackRepoName) {
    String repoName = ScmUrlUtils.resolveRepoName(scmUrl, fallbackRepoName);
    if (repoName == null || repoName.isBlank()) {
      String canonical = ScmUrlUtils.canonicalize(scmUrl);
      repoName = ScmUrlUtils.sanitizePathSegment(canonical.replace("https://", "").replace("http://", ""));
    }
    if (repoName != null && !repoName.isBlank()) {
      return repoName;
    }
    String sanitizedFallback = ScmUrlUtils.sanitizePathSegment(fallbackRepoName == null ? "" : fallbackRepoName);
    return sanitizedFallback.isBlank() ? "unknown-repo" : sanitizedFallback;
  }

  /**
   * Builds end-user summary for downloader completion with success/failure counts.
   */
  static String buildDownloadSummaryMessage(int totalRequests, Map<String, String> failures) {
    int failed = failures == null ? 0 : failures.size();
    int succeeded = Math.max(0, totalRequests - failed);
    StringBuilder sb = new StringBuilder();
    sb.append("Dependencies download task finished!\n")
        .append("Successful: ").append(succeeded).append('\n')
        .append("Failed: ").append(failed);
    String details = formatFailedDependencies(failures);
    if (!details.isBlank()) {
      sb.append("\n\n").append(details);
    }
    return sb.toString();
  }

  private static String formatFailedDependencies(Map<String, String> failures) {
    if (failures == null || failures.isEmpty()) {
      return "";
    }
    Map<String, String> sorted = new LinkedHashMap<>();
    failures.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(Comparator.nullsLast(String::compareTo)))
        .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));

    StringBuilder sb = new StringBuilder("Failed dependencies:\n");
    int shown = 0;
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      if (shown >= 10) {
        break;
      }
      String repoName = repoNameFromRepoKey(entry.getKey());
      String code = failureCode(entry.getValue());
      String reason = failureReason(entry.getValue());
      sb.append("- ").append(repoName);
      if (!code.isBlank()) {
        sb.append(" (").append(code).append(')');
      }
      if (!reason.isBlank()) {
        sb.append(" - ").append(reason);
      }
      sb.append('\n');
      shown++;
    }
    int remaining = sorted.size() - shown;
    if (remaining > 0) {
      sb.append("... and ").append(remaining).append(" more");
    }
    return sb.toString().trim();
  }

  private static String repoNameFromRepoKey(String repoKey) {
    if (repoKey == null || repoKey.isBlank()) {
      return "unknown-repo";
    }
    try {
      Path path = Path.of(repoKey);
      Path fileName = path.getFileName();
      if (fileName == null || fileName.toString().isBlank()) {
        return repoKey;
      }
      return fileName.toString();
    } catch (InvalidPathException e) {
      return repoKey;
    }
  }

  private static String failureCode(String status) {
    if (status == null || !status.startsWith("FAILED:")) {
      return "";
    }
    int end = status.indexOf(' ');
    if (end < 0) {
      end = status.length();
    }
    return status.substring("FAILED:".length(), end);
  }

  private static String failureReason(String status) {
    if (status == null || status.isBlank()) {
      return "";
    }
    int messageIndex = status.indexOf(" message=");
    if (messageIndex < 0) {
      return "";
    }
    String reason = status.substring(messageIndex + " message=".length()).trim();
    if (reason.isBlank()) {
      return "";
    }
    if (reason.length() > 120) {
      return reason.substring(0, 120) + "...";
    }
    return reason;
  }

  private record DownloadResult(boolean success, String message) {
  }

  private static final class NoOpTransportConfigCallback implements TransportConfigCallback {
    @Override
    public void configure(Transport transport) {
      System.setProperty("http.maxRedirects", String.valueOf(MAX_HTTP_REDIRECTS));
      if (transport instanceof TransportHttp transportHttp) {
        transportHttp.setUseSmartHttp(true);
      }
    }
  }
}

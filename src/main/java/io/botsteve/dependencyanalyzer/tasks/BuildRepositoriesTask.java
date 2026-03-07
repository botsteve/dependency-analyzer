package io.botsteve.dependencyanalyzer.tasks;

import static io.botsteve.dependencyanalyzer.service.MavenInvokerService.getMavenInvokerResult;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.getErrorAlertAndCloseProgressBar;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showAlert;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showError;
import static io.botsteve.dependencyanalyzer.utils.JavaVersionResolver.JDKS;
import static io.botsteve.dependencyanalyzer.utils.JavaVersionResolver.getJavaVersionMaven;
import static io.botsteve.dependencyanalyzer.utils.JavaVersionResolver.resolveJavaPathToBeUsed;
import static io.botsteve.dependencyanalyzer.utils.JavaVersionResolver.resolveJavaVersionToEnvProperty;
import static io.botsteve.dependencyanalyzer.utils.Utils.concatenateRepoNames;
import static io.botsteve.dependencyanalyzer.utils.Utils.getPropertyFromSetting;
import static io.botsteve.dependencyanalyzer.utils.Utils.getThirdPartyRepositoriesPath;
import io.botsteve.dependencyanalyzer.utils.ToolchainsGenerator;
import io.botsteve.dependencyanalyzer.utils.Utils;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.eclipse.jgit.api.Git;
import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import io.botsteve.dependencyanalyzer.service.GradleDependencyAnalyzerService;
import io.botsteve.dependencyanalyzer.utils.JavaVersionResolver;
import io.botsteve.dependencyanalyzer.utils.LogUtils;
import io.botsteve.dependencyanalyzer.utils.OperationStatus;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Data
@EqualsAndHashCode(callSuper = false)
public class BuildRepositoriesTask extends Task<Map<String, String>> {

  private static final Logger log = LoggerFactory.getLogger(BuildRepositoriesTask.class);

  private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
  private static final String GRADLE_WRAPPER = IS_WINDOWS ? "gradlew.bat" : "./gradlew";
  private static final String[] GRADLE_COMMAND = {GRADLE_WRAPPER, "clean", "build", "--info"};
  private static final String[] GRADLE_STOP_COMMAND = {GRADLE_WRAPPER, "--stop"};
  private static final String MAVEN_OPTS = "-Dmaven.compiler.fork=true -DargLine=\"-Xmx2g\"";
  private static final String JAVA_HOME = "JAVA_HOME";
  public static final String RETRY_WITH_LOWER_VERSION = "Failed to build using java version {}, retry with lower version";
  public static final String ALL_BUILDS_FAILED = "All attempts to build failed.";
  private final ProgressBar progressBar;
  private final Label progressLabel;
  private String currentRepo;
  private Set<String> reposBuildSuccessfully = new HashSet<>();
  private Map<String, String> repoToBuildStatus = new HashMap<>(); // Renamed from reposBuildSuccessfullyToJavaVersion
  private Set<String> reposBuildFailed = new HashSet<>();
  private static String currentJavaVersionUsed;
  private final String projectName;
  private String currentCommandExecuted;
  private final Set<String> selectedScmUrls;

  /**
   * Creates a build task for all discovered third-party repositories.
   *
   * @param progressBar progress bar bound to task state
   * @param progressLabel progress label bound to task messages
   * @param projectName current project name used for repository root resolution
   */
  public BuildRepositoriesTask(ProgressBar progressBar, Label progressLabel, String projectName) {
    this(progressBar, progressLabel, projectName, null);
  }

  /**
   * Creates a build task constrained to selected SCM URLs.
   *
   * @param progressBar progress bar bound to task state
   * @param progressLabel progress label bound to task messages
   * @param projectName current project name used for repository root resolution
   * @param selectedScmUrls SCM URLs selected by the user; empty means build every discovered repository
   */
  public BuildRepositoriesTask(ProgressBar progressBar, Label progressLabel, String projectName, Set<String> selectedScmUrls) {
    this.progressBar = progressBar;
    this.progressLabel = progressLabel;
    this.projectName = projectName;
    this.selectedScmUrls = selectedScmUrls;
  }

  @Override
  protected void failed() {
    super.failed();
    Throwable exception = getException();
    if (exception != null) {
      log.error(exception.getMessage(), exception);
      getErrorAlertAndCloseProgressBar(String.format("Build failed for %s repository !", currentRepo),
                                       progressBar,
                                       progressLabel);
    }
  }

  @Override
  protected void succeeded() {
    showAlert("Dependencies build task finished! \n" +
              concatenateRepoNames("Repos build successfully:", reposBuildSuccessfully) + "\n\n" +
              concatenateRepoNames("Repos build failed:", reposBuildFailed));
    progressBar.setVisible(false);
    progressLabel.setVisible(false);
  }

  /**
   * Executes repository discovery, toolchains generation, and build orchestration.
   *
   * @return map of repository keys to structured build status messages
   * @throws Exception when repository discovery or build orchestration fails
   */
  @Override
  protected Map<String, String> call() throws Exception {
    var repositoriesPath = getThirdPartyRepositoriesPath(projectName);
    if (isDownloadRepositoriesEmpty(repositoriesPath)) {
      throw new DependencyAnalyzerException("No 3rd-party repositories found!");
    }
    
    // Generate toolchains.xml before build
    try {
        ToolchainsGenerator.generateToolchainsXml(Utils.loadSettings());
    } catch (Exception e) {
        log.warn("Failed to generate toolchains.xml, Maven builds might fail if exact JDKs are required.", e);
    }
    
    buildProject(repositoriesPath);
    return repoToBuildStatus;
  }

  private boolean isDownloadRepositoriesEmpty(String repositoriesPath) {
    File repositoriesDir = new File(repositoriesPath);

    if (repositoriesDir.isDirectory()) {
      File[] repositories = listBuildableRepositories(repositoriesDir);
      if (repositories == null || repositories.length == 0) {
        Platform.runLater(() -> showError("No 3rd-party dependencies downloaded!"));
        return true;
      }
    }
    return false;
  }

  /**
   * Builds all eligible repositories under the given root and records per-repository status.
   *
   * @param repositoriesPath root directory that contains downloaded repositories
   */
  public void buildProject(String repositoriesPath) {
    File repositoriesDir = new File(repositoriesPath);

    if (repositoriesDir.isDirectory()) {
      File[] repositories = listBuildableRepositories(repositoriesDir);
      updateMessage("Clean up exiting download repos!");
      if (repositories != null) {
        for (File repo : repositories) {
          String repoKey = toRepoKey(repositoriesDir, repo);
          if (shouldSkipRepo(repo)) {
            log.debug("Skipping repository not selected: {}", repoKey);
            continue;
          }
          log.info("Building repository: {}", repoKey);
          currentRepo = repoKey;
          updateMessage("Building repository: " + repoKey);
          String operationId = OperationStatus.createOperationId("BUILD");
          long startNanos = System.nanoTime();
          try {
            buildRepository(repo);
            reposBuildSuccessfully.add(currentRepo);
            repoToBuildStatus.put(currentRepo,
                OperationStatus.success(operationId,
                    startNanos,
                    "Built with " + formatJavaVersion(currentJavaVersionUsed) + " (Command: " + currentCommandExecuted + ")"));
          } catch (Exception e) {
            reposBuildFailed.add(currentRepo);
            repoToBuildStatus.put(currentRepo,
                OperationStatus.failure("BUILD_FAILURE",
                    operationId,
                    startNanos,
                    (e.getMessage() == null ? "Unknown error" : e.getMessage())
                        + (currentCommandExecuted != null ? " (Command: " + currentCommandExecuted + ")" : "")));
          }
        }
      } else {
        log.error("No repositories found in the directory.");
        throw new DependencyAnalyzerException("No repositories found in the directory");
      }
    } else {
      log.error("{} is not a directory.", repositoriesPath);
      throw new DependencyAnalyzerException("Not a project directory: " + repositoriesPath);
    }
  }

  private File[] listBuildableRepositories(File repositoriesDir) {
    if (repositoriesDir == null || !repositoriesDir.isDirectory()) {
      return new File[0];
    }

    try (Stream<Path> stream = Files.walk(repositoriesDir.toPath())) {
      return stream
          .filter(Files::isDirectory)
          .map(Path::toFile)
          .filter(this::isBuildableRepositoryDirectory)
          .toArray(File[]::new);
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to scan downloaded repositories directory", e);
    }
  }

  private boolean isBuildableRepositoryDirectory(File repoDirectory) {
    if (repoDirectory == null || !repoDirectory.isDirectory()) {
      return false;
    }
    if (!new File(repoDirectory, ".git").exists()) {
      return false;
    }
    return new File(repoDirectory, "build.gradle").exists()
        || new File(repoDirectory, "build.gradle.kts").exists()
        || new File(repoDirectory, "pom.xml").exists()
        || new File(repoDirectory, "build.xml").exists();
  }

  private boolean shouldSkipRepo(File repo) {
    if (selectedScmUrls == null || selectedScmUrls.isEmpty()) {
      return false;
    }

    String normalizedOriginUrl = normalizeScmUrl(readOriginUrl(repo));
    if (normalizedOriginUrl.isBlank()) {
      return true;
    }

    return selectedScmUrls.stream()
        .flatMap(url -> expandMirrorCandidates(url).stream())
        .map(BuildRepositoriesTask::normalizeScmUrl)
        .noneMatch(normalizedOriginUrl::equals);
  }

  private static String toRepoKey(File repositoriesDir, File repo) {
    if (repo == null) {
      return "";
    }
    Path repoPath = repo.toPath().toAbsolutePath().normalize();
    return repoPath.toString();
  }

  private static Set<String> expandMirrorCandidates(String url) {
    Set<String> out = new LinkedHashSet<>();
    if (url == null || url.isBlank()) {
      return out;
    }
    if (!url.contains("|")) {
      out.add(url.trim());
      return out;
    }
    for (String part : url.split("\\|")) {
      String trimmed = part == null ? "" : part.trim();
      if (!trimmed.isBlank()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private static String readOriginUrl(File repo) {
    if (repo == null || !repo.isDirectory()) {
      return "";
    }
    try (Git git = Git.open(repo)) {
      String url = git.getRepository().getConfig().getString("remote", "origin", "url");
      return url == null ? "" : url;
    } catch (Exception e) {
      return "";
    }
  }

  private static String normalizeScmUrl(String scmUrl) {
    return ScmUrlUtils.normalizeForMatching(scmUrl);
  }

  private void buildRepository(File repo) {
    if (new File(repo, "build.gradle").exists() || new File(repo, "build.gradle.kts").exists()) {
      tryGradleBuildWithDifferentJdks(repo);
    } else if (new File(repo, "pom.xml").exists()) {
      tryMavenBuildWithDifferentJdks(repo);
    } else if (new File(repo, "build.xml").exists()) {
      tryAntBuildWithDifferentJdks(repo);
    } else {
      log.warn("No recognizable build file found in {}", repo.getName());
      throw new DependencyAnalyzerException("No recognizable build file (pom.xml, build.gradle, build.xml) found");
    }
  }

  private void tryMavenBuildWithDifferentJdks(File repo) {
    ArrayList<String> jdks = new ArrayList<>(JDKS);
    if (runMavenBuildWithDetectedJavaVersion(repo, jdks)) return;

    String currentJdkPath = System.getenv(JAVA_HOME);
    boolean buildSuccessful = false;

    for (String property : jdks) {
      try {
        runMavenBuild(repo, currentJdkPath);
        currentJavaVersionUsed = property;
        buildSuccessful = true;
        break;
      } catch (Exception e) {
        if (isFatalError(e)) throw (RuntimeException) e;
        log.error(RETRY_WITH_LOWER_VERSION, currentJdkPath);
        currentJdkPath = getPropertyFromSetting(property);
      }
    }

    if (!buildSuccessful) {
      log.error(ALL_BUILDS_FAILED);
      throw new DependencyAnalyzerException(ALL_BUILDS_FAILED);
    }
  }

  private boolean runMavenBuildWithDetectedJavaVersion(File repo, ArrayList<String> jdks) {
    String javaVersionMaven = getJavaVersionMaven(repo);
    String resolvedjdkPath = resolveJavaPathToBeUsed(javaVersionMaven);
    try {
      runMavenBuild(repo, resolvedjdkPath);
      currentJavaVersionUsed = resolveJavaVersionToEnvProperty(javaVersionMaven);
      return true;
    } catch (Exception e1) {
      if (isFatalError(e1)) throw (RuntimeException) e1;
      log.error("Maven build failed with resolved java version: {}", resolvedjdkPath);
      if ("1.8".equals(javaVersionMaven) || "8".equals(javaVersionMaven)) {
        jdks.remove("JAVA8_HOME");
      } else if ("11.0".equals(javaVersionMaven)) {
        jdks.remove("JAVA11_HOME");
      } else if ("17.0".equals(javaVersionMaven)) {
        jdks.remove("JAVA17_HOME");
      }
    }
    return false;
  }

  private void tryGradleBuildWithDifferentJdks(File repo) {
    // Try detecting the compatible Java version from the Gradle wrapper first
    String detectedJavaVersion = GradleDependencyAnalyzerService.detectJavaVersionFromGradleWrapper(repo);
    if (detectedJavaVersion != null) {
      String detectedJdkPath = JavaVersionResolver.resolveJavaPathToBeUsed(detectedJavaVersion);
      if (detectedJdkPath != null && !detectedJdkPath.isEmpty()) {
        log.info("Detected compatible Java version {} for Gradle wrapper, trying JAVA_HOME={}", detectedJavaVersion, detectedJdkPath);
        try {
          runGradleBuild(repo, detectedJdkPath, GRADLE_STOP_COMMAND);
          runGradleBuild(repo, detectedJdkPath, GRADLE_COMMAND);
          currentJavaVersionUsed = JavaVersionResolver.resolveJavaVersionToEnvProperty(detectedJavaVersion);
          return;
        } catch (Exception e) {
          if (isFatalError(e)) throw (RuntimeException) e;
          log.warn("Gradle build failed with detected JDK ({}), falling back to brute-force approach", detectedJdkPath);
        }
      }
    }

    // Fallback: try all configured JDKs
    String currentJdkPath = System.getenv(JAVA_HOME);
    boolean buildSuccessful = false;

    for (String property : JDKS) {
      try {
        runGradleBuild(repo, currentJdkPath, GRADLE_STOP_COMMAND);
        runGradleBuild(repo, currentJdkPath, GRADLE_COMMAND);
        buildSuccessful = true;
        currentJavaVersionUsed = property;
        break;
      } catch (Exception e) {
        if (isFatalError(e)) throw (RuntimeException) e;
        log.error(RETRY_WITH_LOWER_VERSION, currentJdkPath);
        currentJdkPath = getPropertyFromSetting(property);
      }
    }

    if (!buildSuccessful) {
      log.error(ALL_BUILDS_FAILED);
      throw new DependencyAnalyzerException(ALL_BUILDS_FAILED);
    }
  }

  private void runGradleBuild(File repo, String jdkPath, String[] command) throws IOException, InterruptedException {
    currentCommandExecuted = String.join(" ", command);
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(repo);
    processBuilder.redirectErrorStream(true);
    processBuilder.environment().put(JAVA_HOME, jdkPath);
    log.info("Executing gradle build command: {} with JAVA_HOME: {}", processBuilder.command(), jdkPath);

    Process process;
    try {
        process = processBuilder.start();
    } catch (IOException e) {
        throw new DependencyAnalyzerException("Failed to start Gradle command. Ensure '" + command[0] + "' exists and is executable.", e);
    }

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.info(line);
      }
    }

    int exitCode = process.waitFor();
    if (exitCode == 0) {
      log.info("Gradle build successful for {}", repo.getName());
    } else {
      throw new DependencyAnalyzerException(String.format("Build failed for %s. See log: %s", repo.getName(), LogUtils.getDefaultLogFilePath()));
    }
  }

  private void tryAntBuildWithDifferentJdks(File repo) {
    String currentJdkPath = System.getenv(JAVA_HOME);
    boolean buildSuccessful = false;

    for (String property : JDKS) {
      try {
        runAntBuild(repo, currentJdkPath);
        buildSuccessful = true;
        currentJavaVersionUsed = property;
        break;
      } catch (Exception e) {
        if (isFatalError(e)) throw (RuntimeException) e;
        log.error(RETRY_WITH_LOWER_VERSION, currentJdkPath);
        currentJdkPath = getPropertyFromSetting(property);
      }
    }

    if (!buildSuccessful) {
      log.error(ALL_BUILDS_FAILED);
      throw new DependencyAnalyzerException(ALL_BUILDS_FAILED);
    }
  }
  private boolean isFatalError(Exception e) {
    String msg = e.getMessage();
      if (msg == null) return false;
      return msg.contains("MAVEN_HOME") || 
             msg.contains("command not found") || 
             msg.contains("executable not found") || 
             msg.contains("is not set") ||
             msg.contains("Please ensure");
  }

  private void runAntBuild(File repo, String jdkPath) throws IOException, InterruptedException {
    String antCommand = IS_WINDOWS ? "ant.bat" : "ant";
    currentCommandExecuted = antCommand + " -verbose";
    ProcessBuilder processBuilder = new ProcessBuilder(antCommand, "-verbose");
    
    processBuilder.directory(repo);
    processBuilder.redirectErrorStream(true);
    if (jdkPath != null && !jdkPath.isEmpty()) {
        processBuilder.environment().put(JAVA_HOME, jdkPath);
    }
    
    log.info("Executing Ant build command: {} with JAVA_HOME: {}", antCommand, jdkPath);

    Process process;
    try {
        process = processBuilder.start();
    } catch (IOException e) {
        throw new DependencyAnalyzerException("Failed to start Ant command. Please ensure '" + antCommand + "' is installed and in your PATH.", e);
    }

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.info(line);
      }
    }

    int exitCode = process.waitFor();
    if (exitCode == 0) {
      log.info("Ant build successful for {}", repo.getName());
    } else {
      throw new DependencyAnalyzerException(String.format("Ant build failed for %s. See log: %s", repo.getName(), LogUtils.getDefaultLogFilePath()));
    }
  }

  private void runMavenBuild(File repo, String jdkPath) {
    try {
      currentCommandExecuted = "mvn clean package";
      getMavenInvokerResult(repo.getAbsolutePath(), "", "clean package", MAVEN_OPTS, jdkPath);
    } catch (Exception e) {
      log.error("Building repository failed, retry with new file permissions", e);
      changeDirectoryPermissions(repo);
      currentCommandExecuted = "mvn package";
      getMavenInvokerResult(repo.getAbsolutePath(), "", "package", MAVEN_OPTS, jdkPath);
    }
  }

  /**
   * Recursively applies writable/executable permissions to recover from build file permission issues.
   *
   * @param directory directory or file path that should receive relaxed permissions
   */
  public static void changeDirectoryPermissions(File directory) {
    try {
      // Set read and write permissions for owner, group, and others
      Set<PosixFilePermission> perms = new HashSet<>();
      perms.add(PosixFilePermission.OWNER_READ);
      perms.add(PosixFilePermission.OWNER_WRITE);
      perms.add(PosixFilePermission.OWNER_EXECUTE);
      perms.add(PosixFilePermission.GROUP_READ);
      perms.add(PosixFilePermission.GROUP_WRITE);
      perms.add(PosixFilePermission.GROUP_EXECUTE);
      perms.add(PosixFilePermission.OTHERS_READ);
      perms.add(PosixFilePermission.OTHERS_WRITE);
      perms.add(PosixFilePermission.OTHERS_EXECUTE);

      // Convert permissions to file attributes
      Path path = Paths.get(directory.getAbsolutePath());
      Files.setPosixFilePermissions(path, perms);

      log.debug("Permissions changed successfully for directory: {}", directory.getAbsolutePath());

      // Recursively apply permissions to all files and directories
      if (directory.isDirectory()) {
        File[] files = directory.listFiles();
        if (files != null) {
          for (File file : files) {
            changeDirectoryPermissions(file);
          }
        }
      }
    } catch (Exception e) {
      log.warn("Failed to change permissions for {}: {}", directory.getAbsolutePath(), e.getMessage());
    }
  }

  private String formatJavaVersion(String versionKey) {
    if (versionKey == null) return "Unknown";
    return switch (versionKey) {
        case "JAVA21_HOME" -> "Java 21";
        case "JAVA17_HOME" -> "Java 17";
        case "JAVA11_HOME" -> "Java 11";
        case "JAVA8_HOME" -> "Java 8";
        case "JAVA_HOME" -> "System Java (" + System.getProperty("java.version") + ")";
        default -> versionKey;
    };
  }
}

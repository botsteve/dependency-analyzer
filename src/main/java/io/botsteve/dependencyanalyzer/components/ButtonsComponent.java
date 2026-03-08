package io.botsteve.dependencyanalyzer.components;

import static io.botsteve.dependencyanalyzer.utils.FxUtils.getErrorAlertAndCloseProgressBar;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showAlert;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showError;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showTextDialog;
import static io.botsteve.dependencyanalyzer.utils.Utils.arePropertiesConfiguredAndValid;
import static io.botsteve.dependencyanalyzer.utils.Utils.collectLatestVersions;
import static io.botsteve.dependencyanalyzer.utils.Utils.getFourthPartyRepositoriesPath;
import static io.botsteve.dependencyanalyzer.utils.Utils.getMissingOrInvalidJdkSettings;
import static io.botsteve.dependencyanalyzer.utils.Utils.getThirdPartyRepositoriesPath;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.model.ProjectType;
import io.botsteve.dependencyanalyzer.tasks.BuildRepositoriesTask;
import io.botsteve.dependencyanalyzer.tasks.DependencyDownloaderTask;
import io.botsteve.dependencyanalyzer.tasks.DependencyLoadingTask;
import io.botsteve.dependencyanalyzer.tasks.JdkDownloadTask;
import io.botsteve.dependencyanalyzer.utils.LogUtils;
import io.botsteve.dependencyanalyzer.service.LicenseAggregationService;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import io.botsteve.dependencyanalyzer.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ButtonsComponent {

  private static final Logger log = LoggerFactory.getLogger(ButtonsComponent.class);

  private static AtomicBoolean isDownloaded = new AtomicBoolean(false);
  private final BooleanProperty isTaskRunning = new SimpleBooleanProperty(false);
  private final BooleanProperty isJdkDownloadRunning = new SimpleBooleanProperty(false);
  private final TableViewComponent tableViewComponent;
  private Task<?> activeTask;

  public ButtonsComponent(TableViewComponent tableViewComponent) {
    this.tableViewComponent = tableViewComponent;
  }

  /**
   * Builds the main application toolbar and wires task-state gating for all actions.
   */
  public ToolBar getToolBar(Stage primaryStage, ProgressBar progressBar, Label progressLabel) {
    Button openButton = createOpenDirectoryButton(primaryStage, progressBar, progressLabel);
    Button downloadThirdPartyButton = createDownloadThirdPartyButton(progressBar, progressLabel);
    Button downloadFourthPartyButton = createDownloadFourthPartyButton(progressBar, progressLabel);
    Button downloadJdksButton = createDownloadRequiredJdksButton(progressBar, progressLabel);
    Button buildSelectedButton = createBuildSelectedButton(progressBar, progressLabel);
    Button buildAggregatedLicenseButton = createBuildAggregatedLicenseButton(progressBar, progressLabel);

    openButton.setTooltip(new Tooltip("Open a Maven/Gradle project directory and load dependencies"));
    downloadThirdPartyButton.setTooltip(new Tooltip("Download selected direct 3rd-party dependencies"));
    downloadFourthPartyButton.setTooltip(new Tooltip("Download 4th-party dependencies for selected 3rd-party roots"));
    downloadJdksButton.setTooltip(new Tooltip("Download and auto-configure required JDKs (8, 11, 17, 21)"));
    buildSelectedButton.setTooltip(new Tooltip("Build selected downloaded 3rd-party dependencies"));
    buildAggregatedLicenseButton.setTooltip(new Tooltip("Generate a consolidated license notice for selected dependencies"));
    
    openButton.disableProperty().bind(isTaskRunning);
    downloadThirdPartyButton.disableProperty().bind(isTaskRunning);
    downloadFourthPartyButton.disableProperty().bind(isTaskRunning);
    downloadJdksButton.disableProperty().bind(isTaskRunning);
    buildSelectedButton.disableProperty().bind(isTaskRunning);
    buildAggregatedLicenseButton.disableProperty().bind(isTaskRunning);
    tableViewComponent.getCleanUpCheckBox().disableProperty().bind(isTaskRunning);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    
    return new ToolBar(openButton,
        downloadThirdPartyButton,
        downloadFourthPartyButton,
        buildSelectedButton,
        buildAggregatedLicenseButton,
        spacer,
        downloadJdksButton);
  }

  /**
   * Exposes read-only state used to disable conflicting UI actions during JDK bootstrap.
   */
  public ReadOnlyBooleanProperty jdkDownloadRunningProperty() {
    return isJdkDownloadRunning;
  }

  /**
   * Creates the "Download Required JDKs" action button.
   */
  public Button createDownloadRequiredJdksButton(ProgressBar progressBar, Label progressLabel) {
    Button downloadButton = new Button("Download Required JDKs");
    downloadButton.setStyle("-fx-background-color: #1e73ff; -fx-text-fill: white; -fx-border-color: #1e73ff; -fx-font-weight: bold;");
    downloadButton.setOnAction(event -> {
      startJdkDownload(progressBar, progressLabel, new LinkedHashSet<>(Utils.REQUIRED_JDK_SETTINGS));
    });
    return downloadButton;
  }

  /**
   * Creates the "Download 4th Party" action button and associated download workflow.
   */
  public Button createDownloadFourthPartyButton(ProgressBar progressBar, Label progressLabel) {
    Button downloadButton = new Button("Download 4th Party");
    downloadButton.setOnAction(event -> {
      Set<DependencyNode> selectedThirdParty = getSelectedThirdPartyDependencies();
      if (selectedThirdParty.isEmpty()) {
        Platform.runLater(() -> showError("No 3rd-party dependencies selected!"));
        return;
      }

      Set<DependencyNode> forthParty = new HashSet<>();
      List<DependencyDownloaderTask.DownloadRequest> downloadRequests = new ArrayList<>();
      Map<DependencyNode, Set<String>> dependencyToRepoKeys = new HashMap<>();

      for (DependencyNode thirdParty : selectedThirdParty) {
        Set<DependencyNode> descendants = new HashSet<>();
        collectChildrenRecursively(thirdParty, descendants);
        Set<DependencyNode> downloadableDescendants = filterDownloadableDependencies(descendants);
        if (downloadableDescendants.isEmpty()) {
          continue;
        }

        forthParty.addAll(downloadableDescendants);
        Map<String, String> urlToVersion = collectLatestVersions(downloadableDescendants);
        Map<String, String> urlToArtifactFallback = new HashMap<>();
        downloadableDescendants.forEach(node ->
            urlToArtifactFallback.putIfAbsent(node.getScmUrl(), node.getArtifactId()));
        String parentRepoName = resolveRepoName(thirdParty.getScmUrl(), thirdParty.getArtifactId());
        if (parentRepoName == null || parentRepoName.isBlank()) {
          parentRepoName = thirdParty.getArtifactId();
        }
        String targetDirectory = getFourthPartyRepositoriesPath(tableViewComponent.getProjectName(), parentRepoName);

        for (DependencyNode dependencyNode : downloadableDescendants) {
          String repoKey = buildRepoKey(targetDirectory, dependencyNode.getScmUrl(), dependencyNode.getArtifactId());
          dependencyToRepoKeys
              .computeIfAbsent(dependencyNode, key -> new LinkedHashSet<>())
              .add(repoKey);
        }

        urlToVersion.forEach((scmUrl, version) ->
            downloadRequests.add(new DependencyDownloaderTask.DownloadRequest(
                scmUrl,
                version,
                targetDirectory,
                urlToArtifactFallback.getOrDefault(scmUrl, ""))));
      }

      if (downloadRequests.isEmpty()) {
        Platform.runLater(() -> showError("No 4th-party dependencies with valid SCM URL found for selected 3rd-party dependencies!"));
        return;
      }

      DependencyDownloaderTask task = new DependencyDownloaderTask(downloadRequests,
          progressBar,
          progressLabel,
          tableViewComponent.getCleanUpCheckBox().isSelected(),
          tableViewComponent.getProjectName());
      runManagedTask(task, progressBar, progressLabel, () -> {
        isDownloaded.set(true);
        Map<String, String> versionToCheckoutTag = task.getValue();
        forthParty.forEach(dependencyNode -> {
          Set<String> repoKeys = dependencyToRepoKeys.getOrDefault(dependencyNode, Set.of());
          String checkoutTag = resolveBestStatus(versionToCheckoutTag, repoKeys);
          if (checkoutTag != null && !checkoutTag.isBlank()) {
            dependencyNode.setCheckoutTag(checkoutTag);
          }
        });
        tableViewComponent.updateTreeViewWithFilteredDependencies(tableViewComponent.getFilterInput().getText());
      });
    });
    return downloadButton;
  }

  private void collectChildrenRecursively(DependencyNode node, Set<DependencyNode> out) {
    if (node == null || node.getChildren() == null) return;
    for (DependencyNode child : node.getChildren()) {
      if (child == null) continue;
      out.add(child);
      collectChildrenRecursively(child, out);
    }
  }

  /**
   * Creates the "Create Aggregated License" action button.
   */
  public Button createBuildAggregatedLicenseButton(ProgressBar progressBar, Label progressLabel) {
    Button buildButton = new Button("Create Aggregated License");
    buildButton.setOnAction(event -> {
      Set<DependencyNode> selectedThirdParty = getSelectedThirdPartyDependencies();
      if (selectedThirdParty.isEmpty()) {
        Platform.runLater(() -> showError("No 3rd-party dependencies selected!"));
        return;
      }

      Set<String> missingDownloadedRepos = findMissingDownloadedThirdPartyRepos(selectedThirdParty);
      if (!missingDownloadedRepos.isEmpty()) {
        Platform.runLater(() -> showError(
            "Missing downloaded 3rd-party repositories for:\n"
                + String.join("\n", missingDownloadedRepos)
                + "\n\nRun Download 3rd Party first."));
        return;
      }

      Task<LicenseAggregationService.LicenseReportGenerationResult> task = new Task<>() {
        @Override
        protected LicenseAggregationService.LicenseReportGenerationResult call() throws Exception {
          updateProgress(0, 1);
          updateMessage("Building per-dependency license reports...");
          LicenseAggregationService service = new LicenseAggregationService(tableViewComponent.getProjectName());
          return service.generateAndStorePublicLicenseReports(selectedThirdParty);
        }
      };

      runManagedTask(task, progressBar, progressLabel, () -> {
        LicenseAggregationService.LicenseReportGenerationResult result = task.getValue();
        if (result == null || result.generatedFiles() == null || result.generatedFiles().isEmpty()) {
          showAlert("No aggregated license reports were generated.");
        } else {
          String summary = buildLicenseReportSummaryMessage(result);
          showTextDialog("Aggregated Licenses", "License generation summary", summary);
        }
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
      });
    });
    return buildButton;
  }

  private Set<String> findMissingDownloadedThirdPartyRepos(Set<DependencyNode> selectedThirdParty) {
    String thirdPartyDirectory = getThirdPartyRepositoriesPath(tableViewComponent.getProjectName());
    Set<String> missing = new LinkedHashSet<>();

    for (DependencyNode dependencyNode : selectedThirdParty) {
      if (dependencyNode == null) {
        continue;
      }
      String repoName = resolveRepoName(dependencyNode.getScmUrl(), dependencyNode.getArtifactId());
      if (repoName == null || repoName.isBlank()) {
        repoName = dependencyNode.getArtifactId();
      }

      Path repoPath = Path.of(thirdPartyDirectory, repoName);
      if (Files.isDirectory(repoPath)) {
        continue;
      }

      String coordinate = String.join(":",
          Objects.toString(dependencyNode.getGroupId(), ""),
          Objects.toString(dependencyNode.getArtifactId(), ""),
          Objects.toString(dependencyNode.getVersion(), ""));
      missing.add(coordinate);
    }
    return missing;
  }

  private static String buildLicenseReportSummaryMessage(LicenseAggregationService.LicenseReportGenerationResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("Aggregated license generation completed.\n\n");
    sb.append("Included dependencies: ").append(result.includedDependencies().size()).append("\n");
    sb.append("Skipped dependencies: ").append(result.skippedDependencies().size()).append("\n");
    sb.append("Generated reports: ").append(result.generatedFiles().size()).append("\n\n");

    sb.append("Included dependencies:\n");
    if (result.includedDependencies().isEmpty()) {
      sb.append("- none\n");
    } else {
      result.includedDependencies().forEach(dep -> sb.append("- ").append(dep).append("\n"));
    }

    sb.append("\nSkipped dependencies:\n");
    if (result.skippedDependencies().isEmpty()) {
      sb.append("- none\n");
    } else {
      result.skippedDependencies().forEach(dep -> sb.append("- ").append(dep).append("\n"));
    }

    sb.append("\nGenerated report files:\n");
    result.generatedFiles().forEach(path -> sb.append("- ").append(path).append("\n"));
    return sb.toString();
  }

  /**
   * Creates the "Build Selected 3rd Party" action button.
   */
  public Button createBuildSelectedButton(ProgressBar progressBar, Label progressLabel) {
    Button buildButton = new Button("Build Selected 3rd Party");
    buildButton.setOnAction(event -> {
      Set<DependencyNode> selectedThirdParty = getSelectedThirdPartyDependencies();
      Set<DependencyNode> downloadableThirdParty = filterDownloadableDependencies(selectedThirdParty);
      if (!arePropertiesConfiguredAndValid()
          || !validateBuildPreconditions(selectedThirdParty, downloadableThirdParty)) {
        return;
      }
      Set<String> selectedRepoNames = downloadableThirdParty.stream()
          .map(DependencyNode::getScmUrl)
          .filter(Objects::nonNull)
          .filter(url -> !url.isBlank())
          .collect(Collectors.toSet());

      BuildRepositoriesTask task = new BuildRepositoriesTask(progressBar, progressLabel, tableViewComponent.getProjectName(), selectedRepoNames);
      runManagedTask(task, progressBar, progressLabel, () -> {
        Map<String, String> successfulBuiltReposToJavaVersion = task.getValue();
        selectedThirdParty
            .forEach(dependencyNode -> {
          String buildStatus = resolveBuildStatusForDependency(successfulBuiltReposToJavaVersion, dependencyNode);
              if (buildStatus != null && !buildStatus.isBlank()) {
                dependencyNode.setBuildWith(buildStatus);
              }
            });
        // simulating refresh
        tableViewComponent.updateTreeViewWithFilteredDependencies(tableViewComponent.getFilterInput().getText());
      });
    });
    return buildButton;
  }

  /**
   * Creates the "Download 3rd Party" action button.
   */
  public Button createDownloadThirdPartyButton(ProgressBar progressBar, Label progressLabel) {
    Button downloadButton = new Button("Download 3rd Party");
    downloadButton.setOnAction(event -> {
      Set<DependencyNode> selectedThirdParty = getSelectedThirdPartyDependencies();
      Set<DependencyNode> downloadableThirdParty = filterDownloadableDependencies(selectedThirdParty);
      Map<String, String> urlToVersion = collectLatestVersions(downloadableThirdParty);
      if (urlToVersion.isEmpty()) {
        Platform.runLater(() -> showError("No 3rd-party dependencies with valid SCM URL selected!"));
        return;
      }

      String thirdPartyDirectory = getThirdPartyRepositoriesPath(tableViewComponent.getProjectName());
      Map<String, String> targetDirectories = new HashMap<>();
      Map<String, String> fallbackRepoNames = new HashMap<>();
      urlToVersion.keySet().forEach(scmUrl -> targetDirectories.put(scmUrl, thirdPartyDirectory));
      downloadableThirdParty.forEach(node -> {
        String scmUrl = node.getScmUrl();
        if (scmUrl == null || scmUrl.isBlank()) {
          return;
        }
        fallbackRepoNames.putIfAbsent(scmUrl, node.getArtifactId());
      });

      DependencyDownloaderTask task = new DependencyDownloaderTask(urlToVersion,
          progressBar,
          progressLabel,
          tableViewComponent.getCleanUpCheckBox().isSelected(),
          tableViewComponent.getProjectName(),
          targetDirectories,
          fallbackRepoNames);
      runManagedTask(task, progressBar, progressLabel, () -> {
        isDownloaded.set(true);
        Map<String, String> versionToCheckoutTag = task.getValue();
        selectedThirdParty.forEach(dependencyNode -> {
          String repoKey = buildRepoKey(thirdPartyDirectory, dependencyNode.getScmUrl(), dependencyNode.getArtifactId());
          String checkoutTag = versionToCheckoutTag.get(repoKey);
          if (checkoutTag != null && !checkoutTag.isBlank()) {
            dependencyNode.setCheckoutTag(checkoutTag);
          }
        });
        tableViewComponent.updateTreeViewWithFilteredDependencies(tableViewComponent.getFilterInput().getText());
      });
    });
    return downloadButton;
  }

  private Set<DependencyNode> getSelectedThirdPartyDependencies() {
    if (tableViewComponent.getAllDependencies() == null) {
      return Set.of();
    }
    return tableViewComponent.getAllDependencies().stream()
        .filter(DependencyNode::isSelected)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<DependencyNode> filterDownloadableDependencies(Set<DependencyNode> dependencies) {
    return dependencies.stream()
        .filter(dep -> dep != null && dep.getScmUrl() != null && !dep.getScmUrl().isBlank())
        .filter(dep -> !"SCM URL not found".equals(dep.getScmUrl()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Creates the "Open Directory" action button and project loading workflow.
   */
  public Button createOpenDirectoryButton(Stage primaryStage, ProgressBar progressBar, Label progressLabel) {
    Button openButton = new Button("Open Directory");
    openButton.setOnAction(event -> {
      String javaHome = System.getenv("JAVA_HOME");
      if (javaHome == null || javaHome.isEmpty()) {
        showError("JAVA_HOME environment variable is not configured, please configure and try again!");
        return;
      }
      DirectoryChooser directoryChooser = new DirectoryChooser();
      File selectedDirectory = directoryChooser.showDialog(primaryStage);
      if (selectedDirectory != null) {
        ProjectType projectType = ProjectType.detect(selectedDirectory);
        if (projectType == ProjectType.UNKNOWN) {
          getErrorAlertAndCloseProgressBar(
              "No recognizable build file found!\n" +
              "Please open the root directory of a Maven (pom.xml) or Gradle (build.gradle) project.",
              progressBar, progressLabel);
          return;
        }
        if (projectType == ProjectType.MAVEN) {
          String mavenHome = System.getenv("MAVEN_HOME");
          if (mavenHome == null || mavenHome.isEmpty()) {
            showError("MAVEN_HOME environment variable is not configured. It is required for Maven projects.");
            return;
          }
        }
        reset();
        tableViewComponent.getProjectNameLabel().setText(selectedDirectory.getName());
        tableViewComponent.setProjectName(selectedDirectory.getName());
        progressLabel.setText("Loading " + projectType.name().toLowerCase() + " dependencies...");

        DependencyLoadingTask task = new DependencyLoadingTask(selectedDirectory.getPath(), progressBar, progressLabel,
            tableViewComponent.getTreeTableView());

        runManagedTask(task, progressBar, progressLabel, () -> {
          tableViewComponent.setAllDependencies(FXCollections.observableSet(task.getValue()));
          tableViewComponent.updateTreeView(tableViewComponent.getAllDependencies());
          tableViewComponent.updateTreeViewWithFilteredDependencies(tableViewComponent.getFilterInput().getText());
          showJdkConfigurationWarningIfNeeded(progressBar, progressLabel);
        });
      }
    });
    return openButton;
  }

  private void showJdkConfigurationWarningIfNeeded(ProgressBar progressBar, Label progressLabel) {
    List<String> missingOrInvalid = getMissingOrInvalidJdkSettings();
    if (missingOrInvalid.isEmpty()) {
      return;
    }

    Alert alert = new Alert(Alert.AlertType.WARNING);
    alert.setTitle("JDK settings not configured");
    alert.setHeaderText("Required JDK paths are missing or invalid");
    alert.setContentText("The following settings are not configured correctly: "
        + String.join(", ", missingOrInvalid)
        + "\n\nUse the 'Download Required JDKs' button to install and configure them automatically.");

    ButtonType downloadType = new ButtonType("Download Required JDKs");
    ButtonType closeType = ButtonType.CANCEL;
    alert.getButtonTypes().setAll(downloadType, closeType);
    alert.showAndWait().ifPresent(selected -> {
      if (selected == downloadType) {
        Set<String> requested = new LinkedHashSet<>(missingOrInvalid);
        log.info("Popup-triggered JDK download requested for settings: {}", requested);
        Platform.runLater(() -> startJdkDownload(progressBar, progressLabel, requested));
      }
    });
  }

  private void startJdkDownload(ProgressBar progressBar, Label progressLabel, Set<String> requestedJdks) {
    Set<String> normalizedRequested = normalizeRequestedJdkSettings(requestedJdks);
    if (normalizedRequested.isEmpty()) {
      showAlert("All required JDK settings are already configured.");
      return;
    }

    log.info("Starting JDK download workflow with requested settings: {}", normalizedRequested);

    JdkDownloadTask task = new JdkDownloadTask(normalizedRequested);
    runManagedTask(task, progressBar, progressLabel, () -> {
      progressBar.setVisible(false);
      progressLabel.setVisible(false);
      Map<String, String> downloaded = task.getValue();
      if (downloaded == null || downloaded.isEmpty()) {
        showAlert("No JDKs were downloaded.");
        return;
      }
      String summary = downloaded.entrySet().stream()
          .map(entry -> "- " + entry.getKey() + " = " + entry.getValue())
          .collect(Collectors.joining("\n"));
      if (downloaded.containsKey("JAVA8_HOME") && isArmHostArchitecture()) {
        summary = summary + "\n\nNote: " + JdkDownloadTask.java8X64Explanation();
      }
      showAlert("JDK download completed and config/env-settings.properties updated:\n" + summary);
    });
  }

  static Set<String> normalizeRequestedJdkSettings(Set<String> requestedJdks) {
    if (requestedJdks == null || requestedJdks.isEmpty()) {
      return Set.of();
    }

    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String required : Utils.REQUIRED_JDK_SETTINGS) {
      if (requestedJdks.contains(required)) {
        normalized.add(required);
      }
    }

    if (!normalized.isEmpty()) {
      return normalized;
    }

    for (String candidate : requestedJdks) {
      if (candidate == null || candidate.isBlank()) {
        continue;
      }
      String upper = candidate.toUpperCase(Locale.ROOT);
      for (String required : Utils.REQUIRED_JDK_SETTINGS) {
        if (upper.contains(required)) {
          normalized.add(required);
        }
      }
    }
    return normalized;
  }

  /**
   * Returns whether current host architecture is ARM/aarch64.
   */
  static boolean isArmHostArchitecture() {
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return arch.contains("arm") || arch.contains("aarch64");
  }


  private void reset() {
    isDownloaded.set(false);
    tableViewComponent.getSelectAllCheckBox().setSelected(false);
    tableViewComponent.getFilterInput().setText("");
    tableViewComponent.getExcludeFilterInput().setText("");
    tableViewComponent.setAllDependencies(FXCollections.observableSet(new HashSet<>()));
    tableViewComponent.setSelectedDependencies(FXCollections.observableSet(new HashSet<>()));
    tableViewComponent.updateTreeView(new HashSet<>());
  }

  private boolean validateBuildPreconditions(Set<DependencyNode> selectedThirdParty,
                                             Set<DependencyNode> downloadableThirdParty) {
    String gateMessage = evaluateBuildGateMessage(selectedThirdParty == null || selectedThirdParty.isEmpty(),
        hasDownloadedThirdPartyRepositories());
    if (!gateMessage.isBlank()) {
      showError(gateMessage);
      return false;
    }
    if (downloadableThirdParty == null || downloadableThirdParty.isEmpty()) {
      showError("No selected 3rd-party dependencies with valid SCM URL!");
      return false;
    }
    return true;
  }

  private boolean hasDownloadedThirdPartyRepositories() {
    String projectName = tableViewComponent.getProjectName();
    if (projectName == null || projectName.isBlank()) {
      return false;
    }
    File thirdPartyDirectory = new File(getThirdPartyRepositoriesPath(projectName));
    if (!thirdPartyDirectory.isDirectory()) {
      return false;
    }
    File[] repositories = thirdPartyDirectory.listFiles(File::isDirectory);
    return repositories != null && repositories.length > 0;
  }

  /**
   * Returns user-facing build gate message for selection/download preconditions.
   */
  static String evaluateBuildGateMessage(boolean noSelection, boolean downloaded) {
    if (noSelection) {
      return "No 3rd-party dependencies selected!";
    }
    if (!downloaded) {
      return "No 3rd-party dependencies downloaded for current project. Run Download 3rd Party first.";
    }
    return "";
  }

  private String resolveBuildStatusForDependency(Map<String, String> buildStatuses, DependencyNode dependencyNode) {
    if (buildStatuses == null || buildStatuses.isEmpty() || dependencyNode == null) {
      return null;
    }

    String thirdPartyRepoKey = buildRepoKey(
        getThirdPartyRepositoriesPath(tableViewComponent.getProjectName()),
        dependencyNode.getScmUrl(),
        dependencyNode.getArtifactId());
    String thirdPartyStatus = buildStatuses.get(thirdPartyRepoKey);
    if (thirdPartyStatus != null && !thirdPartyStatus.isBlank()) {
      return thirdPartyStatus;
    }

    String repoName = resolveRepoName(dependencyNode.getScmUrl(), dependencyNode.getArtifactId());
    if (repoName.isBlank()) {
      return null;
    }

    String fourthPartyRoot = Path.of(getFourthPartyRepositoriesPath(tableViewComponent.getProjectName()))
        .normalize()
        .toString();
    String suffix = File.separator + repoName;
    String failedCandidate = null;
    for (Map.Entry<String, String> entry : buildStatuses.entrySet()) {
      String key = entry.getKey();
      if (key == null || !key.startsWith(fourthPartyRoot) || !key.endsWith(suffix)) {
        continue;
      }
      String status = entry.getValue();
      if (status == null) {
        continue;
      }
      if (!status.startsWith("FAILED:")) {
        return status;
      }
      if (failedCandidate == null) {
        failedCandidate = status;
      }
    }
    return failedCandidate;
  }

  private static String resolveBestStatus(Map<String, String> statusByRepoKey, Set<String> repoKeys) {
    if (statusByRepoKey == null || statusByRepoKey.isEmpty() || repoKeys == null || repoKeys.isEmpty()) {
      return null;
    }

    String failedCandidate = null;
    for (String repoKey : repoKeys) {
      String status = statusByRepoKey.get(repoKey);
      if (status == null) {
        continue;
      }
      if (!status.startsWith("FAILED:")) {
        return status;
      }
      if (failedCandidate == null) {
        failedCandidate = status;
      }
    }
    return failedCandidate;
  }

  private static String buildRepoKey(String targetDirectory, String scmUrl, String fallbackArtifactId) {
    String repoName = resolveRepoName(scmUrl, fallbackArtifactId);
    return ScmUrlUtils.toRepoKey(targetDirectory, repoName);
  }

  private static String resolveRepoName(String scmUrl, String fallbackArtifactId) {
    return ScmUrlUtils.resolveRepoName(scmUrl, fallbackArtifactId);
  }

  private void runManagedTask(Task<?> task,
                              ProgressBar progressBar,
                              Label progressLabel,
                              Runnable onSuccess) {
    boolean jdkTask = task instanceof JdkDownloadTask;
    progressBar.progressProperty().unbind();
    progressLabel.textProperty().unbind();
    if (activeTask != null) {
      progressBar.progressProperty().unbind();
      progressLabel.textProperty().unbind();
    }
    activeTask = task;
    isTaskRunning.set(true);
    if (jdkTask) {
      isJdkDownloadRunning.set(true);
    }

    progressBar.setVisible(true);
    progressLabel.setVisible(true);
    if (jdkTask) {
      progressBar.setProgress(0.0);
      progressLabel.setText("Preparing JDK downloads...");
      progressBar.progressProperty().bind(task.progressProperty());
    } else {
      progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
      progressLabel.setText("Starting " + task.getClass().getSimpleName() + "...");
    }
    progressLabel.textProperty().bind(task.messageProperty());

    task.setOnSucceeded(event -> {
      if (jdkTask) {
        progressBar.progressProperty().unbind();
      }
      progressLabel.textProperty().unbind();
      isTaskRunning.set(false);
      if (jdkTask) {
        isJdkDownloadRunning.set(false);
      }
      progressBar.setVisible(false);
      progressLabel.setVisible(false);
      if (onSuccess != null) {
        onSuccess.run();
      }
    });

    task.setOnFailed(event -> {
      if (jdkTask) {
        progressBar.progressProperty().unbind();
      }
      progressLabel.textProperty().unbind();
      isTaskRunning.set(false);
      if (jdkTask) {
        isJdkDownloadRunning.set(false);
      }
      Throwable ex = task.getException();
      log.error("Task {} failed", task == null ? "unknown" : task.getClass().getSimpleName(), ex);
      if (!(task instanceof DependencyDownloaderTask) && !(task instanceof BuildRepositoriesTask)) {
        String failureMessage = buildTaskFailureMessage(task, ex);
        getErrorAlertAndCloseProgressBar(failureMessage, progressBar, progressLabel);
      }
    });

    task.setOnCancelled(event -> {
      if (jdkTask) {
        progressBar.progressProperty().unbind();
      }
      progressLabel.textProperty().unbind();
      isTaskRunning.set(false);
      if (jdkTask) {
        isJdkDownloadRunning.set(false);
      }
      progressBar.setVisible(false);
      progressLabel.setVisible(false);
    });

    Thread worker = new Thread(task, task.getClass().getSimpleName() + "-worker");
    worker.setDaemon(true);
    worker.start();
  }

  /**
   * Builds a detailed task failure message including root cause and log file path.
   */
  static String buildTaskFailureMessage(Task<?> task, Throwable ex) {
    String taskName = task == null ? "Task" : task.getClass().getSimpleName();
    String topMessage = (ex == null || ex.getMessage() == null || ex.getMessage().isBlank())
        ? "Unknown error"
        : ex.getMessage();

    Throwable root = ex;
    while (root != null && root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String rootMessage = (root == null || root.getMessage() == null || root.getMessage().isBlank())
        ? ""
        : root.getMessage();

    StringBuilder message = new StringBuilder(taskName)
        .append(" failed: ")
        .append(topMessage);
    if (!rootMessage.isBlank() && !rootMessage.equals(topMessage)) {
      message.append("\nRoot cause: ").append(rootMessage);
    }
    message.append("\nCheck logs: ").append(LogUtils.getDefaultLogFilePath());
    return message.toString();
  }
}

package io.botsteve.dependencyanalyzer.components;

import static io.botsteve.dependencyanalyzer.utils.FxUtils.getErrorAlertAndCloseProgressBar;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showAlert;
import static io.botsteve.dependencyanalyzer.utils.FxUtils.showError;
import static io.botsteve.dependencyanalyzer.utils.Utils.arePropertiesConfiguredAndValid;
import static io.botsteve.dependencyanalyzer.utils.Utils.collectLatestVersions;
import static io.botsteve.dependencyanalyzer.utils.Utils.getFourthPartyRepositoriesPath;
import static io.botsteve.dependencyanalyzer.utils.Utils.getThirdPartyRepositoriesPath;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToolBar;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.tasks.BuildRepositoriesTask;
import io.botsteve.dependencyanalyzer.tasks.DependencyDownloaderTask;
import io.botsteve.dependencyanalyzer.tasks.DependencyLoadingTask;
import io.botsteve.dependencyanalyzer.service.LicenseAggregationService;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;

public class ButtonsComponent {

  private static AtomicBoolean isDownloaded = new AtomicBoolean(false);
  private final javafx.beans.property.BooleanProperty isTaskRunning = new javafx.beans.property.SimpleBooleanProperty(false);
  private final TableViewComponent tableViewComponent;
  private Task<?> activeTask;

  public ButtonsComponent(TableViewComponent tableViewComponent) {
    this.tableViewComponent = tableViewComponent;
  }

  public ToolBar getToolBar(Stage primaryStage, ProgressBar progressBar, Label progressLabel) {
    var openButton = createOpenDirectoryButton(primaryStage, progressBar, progressLabel);
    var downloadThirdPartyButton = createDownloadThirdPartyButton(progressBar, progressLabel);
    var downloadFourthPartyButton = createDownloadFourthPartyButton(progressBar, progressLabel);
    var buildSelectedButton = createBuildSelectedButton(progressBar, progressLabel);
    var buildAggregatedLicenseButton = createBuildAggregatedLicenseButton(progressBar, progressLabel);

    openButton.setTooltip(new Tooltip("Open a Maven/Gradle project directory and load dependencies"));
    downloadThirdPartyButton.setTooltip(new Tooltip("Download selected direct 3rd-party dependencies"));
    downloadFourthPartyButton.setTooltip(new Tooltip("Download 4th-party dependencies for selected 3rd-party roots"));
    buildSelectedButton.setTooltip(new Tooltip("Build selected downloaded 3rd-party dependencies"));
    buildAggregatedLicenseButton.setTooltip(new Tooltip("Generate a consolidated license notice for selected dependencies"));
    
    openButton.disableProperty().bind(isTaskRunning);
    downloadThirdPartyButton.disableProperty().bind(isTaskRunning);
    downloadFourthPartyButton.disableProperty().bind(isTaskRunning);
    buildSelectedButton.disableProperty().bind(isTaskRunning);
    buildAggregatedLicenseButton.disableProperty().bind(isTaskRunning);
    tableViewComponent.getCleanUpCheckBox().disableProperty().bind(isTaskRunning);
    
    return new ToolBar(openButton, downloadThirdPartyButton, downloadFourthPartyButton, buildSelectedButton,
                       buildAggregatedLicenseButton);
  }

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
        var versionToCheckoutTag = task.getValue();
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
    for (var child : node.getChildren()) {
      if (child == null) continue;
      out.add(child);
      collectChildrenRecursively(child, out);
    }
  }

  public Button createBuildAggregatedLicenseButton(ProgressBar progressBar, Label progressLabel) {
    Button buildButton = new Button("Create Aggregated License");
    buildButton.setOnAction(event -> {
      if (tableViewComponent.getSelectedDependencies().isEmpty()) {
        Platform.runLater(() -> showError("No dependencies selected!"));
        return;
      }

      FileChooser fileChooser = new FileChooser();
      fileChooser.setTitle("Save Aggregated License Report");
      fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown Files", "*.md"));
      fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
      fileChooser.setInitialFileName("PUBLIC-LICENSES-NOTICE.md");
      File out = fileChooser.showSaveDialog(buildButton.getScene() == null ? null : buildButton.getScene().getWindow());
      if (out == null) return;

      Set<io.botsteve.dependencyanalyzer.model.DependencyNode> selectedSnapshot =
          new java.util.LinkedHashSet<>(tableViewComponent.getSelectedDependencies());

      javafx.concurrent.Task<Void> task = new javafx.concurrent.Task<>() {
        @Override
        protected Void call() throws Exception {
          updateProgress(0, 1);
          updateMessage("Building license report...");
          var service = new LicenseAggregationService(tableViewComponent.getProjectName());
          String report = service.generatePublicLicenseReport(selectedSnapshot);
          java.nio.file.Files.writeString(out.toPath(), report, java.nio.charset.StandardCharsets.UTF_8);
          return null;
        }
      };

      runManagedTask(task, progressBar, progressLabel, () -> {
        showAlert("Aggregated license report saved to: " + out.getAbsolutePath());
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
      });
    });
    return buildButton;
  }

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
          .collect(java.util.stream.Collectors.toSet());

      BuildRepositoriesTask task = new BuildRepositoriesTask(progressBar, progressLabel, tableViewComponent.getProjectName(), selectedRepoNames);
      runManagedTask(task, progressBar, progressLabel, () -> {
        var successfulBuiltReposToJavaVersion = task.getValue();
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

  public Button createDownloadThirdPartyButton(ProgressBar progressBar, Label progressLabel) {
    Button downloadButton = new Button("Download 3rd Party");
    downloadButton.setOnAction(event -> {
      Set<DependencyNode> selectedThirdParty = getSelectedThirdPartyDependencies();
      Set<DependencyNode> downloadableThirdParty = filterDownloadableDependencies(selectedThirdParty);
      var urlToVersion = collectLatestVersions(downloadableThirdParty);
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
        var versionToCheckoutTag = task.getValue();
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

  public Button createOpenDirectoryButton(Stage primaryStage, ProgressBar progressBar, Label progressLabel) {
    Button openButton = new Button("Open Directory");
    openButton.setOnAction(event -> {
      var javaHome = System.getenv("JAVA_HOME");
      if (javaHome == null || javaHome.isEmpty()) {
        showError("JAVA_HOME environment variable is not configured, please configure and try again!");
        return;
      }
      DirectoryChooser directoryChooser = new DirectoryChooser();
      File selectedDirectory = directoryChooser.showDialog(primaryStage);
      if (selectedDirectory != null) {
        var projectType = io.botsteve.dependencyanalyzer.model.ProjectType.detect(selectedDirectory);
        if (projectType == io.botsteve.dependencyanalyzer.model.ProjectType.UNKNOWN) {
          getErrorAlertAndCloseProgressBar(
              "No recognizable build file found!\n" +
              "Please open the root directory of a Maven (pom.xml) or Gradle (build.gradle) project.",
              progressBar, progressLabel);
          return;
        }
        if (projectType == io.botsteve.dependencyanalyzer.model.ProjectType.MAVEN) {
          var mavenHome = System.getenv("MAVEN_HOME");
          if (mavenHome == null || mavenHome.isEmpty()) {
            showError("MAVEN_HOME environment variable is not configured. It is required for Maven projects.");
            return;
          }
        }
        reset();
        tableViewComponent.getProjectNameLabel().setText(selectedDirectory.getName());
        tableViewComponent.setProjectName(selectedDirectory.getName());
        progressLabel.setText("Loading " + projectType.name().toLowerCase() + " dependencies...");

        var task = new DependencyLoadingTask(selectedDirectory.getPath(), progressBar, progressLabel,
                                             tableViewComponent.getTreeTableView());

        runManagedTask(task, progressBar, progressLabel, () -> {
          tableViewComponent.setAllDependencies(FXCollections.observableSet(task.getValue()));
          tableViewComponent.updateTreeView(tableViewComponent.getAllDependencies());
          tableViewComponent.updateTreeViewWithFilteredDependencies(tableViewComponent.getFilterInput().getText());
        });
      }
    });
    return openButton;
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
    if (activeTask != null) {
      progressBar.progressProperty().unbind();
      progressLabel.textProperty().unbind();
    }
    activeTask = task;
    isTaskRunning.set(true);

    progressBar.setVisible(true);
    progressLabel.setVisible(true);
    progressBar.progressProperty().bind(task.progressProperty());
    progressLabel.textProperty().unbind();
    progressLabel.textProperty().bind(task.messageProperty());

    task.setOnSucceeded(event -> {
      progressBar.progressProperty().unbind();
      progressLabel.textProperty().unbind();
      isTaskRunning.set(false);
      if (onSuccess != null) {
        onSuccess.run();
      }
    });

    task.setOnFailed(event -> {
      progressBar.progressProperty().unbind();
      progressLabel.textProperty().unbind();
      isTaskRunning.set(false);
      Throwable ex = task.getException();
      if (ex != null && !(task instanceof DependencyDownloaderTask) && !(task instanceof BuildRepositoriesTask)) {
        getErrorAlertAndCloseProgressBar(ex.getMessage(), progressBar, progressLabel);
      }
    });

    task.setOnCancelled(event -> {
      progressBar.progressProperty().unbind();
      progressLabel.textProperty().unbind();
      isTaskRunning.set(false);
      progressBar.setVisible(false);
      progressLabel.setVisible(false);
    });

    new Thread(task).start();
  }
}

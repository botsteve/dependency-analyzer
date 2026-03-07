package io.botsteve.dependencyanalyzer.tasks;

import static io.botsteve.dependencyanalyzer.utils.FxUtils.getErrorAlertAndCloseProgressBar;

import java.util.Set;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.model.ProjectType;
import io.botsteve.dependencyanalyzer.service.DependencyAnalyzerService;
import io.botsteve.dependencyanalyzer.service.ScmEnrichmentService;
import io.botsteve.dependencyanalyzer.service.ScmUrlFetcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyLoadingTask extends Task<Set<DependencyNode>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(DependencyLoadingTask.class);

  private final String projectDir;
  private final ProgressBar progressBar;
  private final Label progressLabel;
  private final TreeTableView<DependencyNode> treeTableView;

  /**
   * Creates a background loader for dependency analysis and SCM enrichment.
   *
   * @param projectDir selected project root directory
   * @param progressBar progress indicator bound to task progress
   * @param progressLabel status label bound to task messages
   * @param treeTableView target tree table that receives resolved nodes
   */
  public DependencyLoadingTask(String projectDir, ProgressBar progressBar, Label progressLabel,
                               TreeTableView<DependencyNode> treeTableView) {
    this.projectDir = projectDir;
    this.progressBar = progressBar;
    this.progressLabel = progressLabel;
    this.treeTableView = treeTableView;
  }

  /**
   * Loads dependencies, detects project type, and enriches SCM URLs.
   *
   * @return top-level dependency nodes for the selected project
   * @throws Exception when dependency analysis or enrichment fails
   */
  @Override
  protected Set<DependencyNode> call() throws Exception {
    updateMessage("Loading dependencies...");
    Set<DependencyNode> dependencies = DependencyAnalyzerService.getDependencies(projectDir);

    updateMessage("Fetching and Enriching SCM URLs...");
    ProjectType projectType = DependencyAnalyzerService.getProjectType(projectDir);
    
    // 1. Initial tool-based scan (Maven/Gradle plugins)
    if (projectType == ProjectType.MAVEN) {
      ScmUrlFetcherService.fetchScmUrls(projectDir, dependencies);
    } 
    
    // 2. Unified Remote Enrichment (The "Fill the Gaps" fallback)
    // This now runs for BOTH Maven and Gradle to catch missing info from Parent POMs or Maven Central
    ScmEnrichmentService.fetchScmUrls(dependencies);
    
    return dependencies;
  }

  /**
   * Pushes loaded dependencies into the tree table and hides progress UI.
   */
  @Override
  protected void succeeded() {
    Set<DependencyNode> dependencies = getValue();
    TreeItem<DependencyNode> rootItem = new TreeItem<>(new DependencyNode("Root", "", ""));
    for (DependencyNode node : dependencies) {
      rootItem.getChildren().add(createTreeItem(node));
    }
    treeTableView.setRoot(rootItem);
    progressBar.setVisible(false);
    progressLabel.setVisible(false);
  }

  /**
   * Converts task failure into a user-facing error message and closes progress UI.
   */
  @Override
  protected void failed() {
    super.failed();
    Throwable exception = getException();
    if (exception != null) {
      LOGGER.error(exception.getMessage(), exception);
      ProjectType projectType = DependencyAnalyzerService.getProjectType(projectDir);
      String errorMsg = projectType == ProjectType.GRADLE
          ? "Failed to analyze Gradle project. Make sure the project compiles " +
          "and Gradle Wrapper (gradlew) or system Gradle is available.\n" +
          "Error: " + exception.getMessage()
          : "Make sure the maven project compiles and has a error-free root pom.xml.\n" +
          "If you are behind a proxy, check maven proxy in ~/.m2/settings.xml.";
      getErrorAlertAndCloseProgressBar(errorMsg, progressBar, progressLabel);
    }
  }

  private TreeItem<DependencyNode> createTreeItem(DependencyNode node) {
    TreeItem<DependencyNode> treeItem = new TreeItem<>(node);
    if (node.getChildren() != null) {
      for (DependencyNode child : node.getChildren()) {
        treeItem.getChildren().add(createTreeItem(child));
      }
    }
    return treeItem;
  }
}

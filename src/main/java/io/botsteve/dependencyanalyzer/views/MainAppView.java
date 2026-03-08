package io.botsteve.dependencyanalyzer.views;


import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URL;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import io.botsteve.dependencyanalyzer.components.ButtonsComponent;
import io.botsteve.dependencyanalyzer.components.CheckBoxComponent;
import io.botsteve.dependencyanalyzer.components.ColumnsComponent;
import io.botsteve.dependencyanalyzer.components.MenuComponent;
import io.botsteve.dependencyanalyzer.components.ProgressBoxComponent;
import io.botsteve.dependencyanalyzer.components.TableViewComponent;
import io.botsteve.dependencyanalyzer.model.DependencyNode;

@Data
public class MainAppView {

  private static final Logger log = LoggerFactory.getLogger(MainAppView.class);
  private static final Path STARTUP_TRACE_FILE = Path.of("/tmp", "dependency-analyzer-startup-trace.log");

  private final TableViewComponent tableViewComponent = new TableViewComponent();
  private final ColumnsComponent columnsComponent = new ColumnsComponent(tableViewComponent);
  private final CheckBoxComponent checkBoxComponent = new CheckBoxComponent(tableViewComponent);
  private final ButtonsComponent buttonsComponent = new ButtonsComponent(tableViewComponent);
  private final MenuComponent menuComponent = new MenuComponent(tableViewComponent);
  private final ProgressBoxComponent progressBoxComponent = new ProgressBoxComponent();

  public void start(Stage primaryStage) {
    startupProbe("MainAppView.start entered");

    BorderPane root = new BorderPane();
    Scene scene = new Scene(root, 1000, 800);
    TreeTableView<DependencyNode> treeTableView = tableViewComponent.getTreeTableView();
    TreeTableColumn<DependencyNode, String> dependencyColumn = columnsComponent.getDependencyTreeTableColumn();
    TreeTableColumn<DependencyNode, String> scopeColumn = columnsComponent.getScopeColumn();
    TreeTableColumn<DependencyNode, String> scmColumn = columnsComponent.getSCMTreeTableColumn();
    TreeTableColumn<DependencyNode, Boolean> selectColumn = columnsComponent.getSelectTreeTableColumn();
    TreeTableColumn<DependencyNode, String> checkoutTagColumn = columnsComponent.getCheckoutTagColumn();
    TreeTableColumn<DependencyNode, String> buildWithColumn = columnsComponent.getBuildWithColumn();
    treeTableView.getColumns().add(selectColumn);
    treeTableView.getColumns().add(dependencyColumn);
    treeTableView.getColumns().add(scopeColumn);
    treeTableView.getColumns().add(scmColumn);
    treeTableView.getColumns().add(checkoutTagColumn);
    treeTableView.getColumns().add(buildWithColumn);
    treeTableView.setTreeColumn(dependencyColumn);
    columnsComponent.configureColumnsWidthStyle(selectColumn, dependencyColumn, scopeColumn, scmColumn, checkoutTagColumn, buildWithColumn);
    checkBoxComponent.configureCheckBoxAction();

    ProgressBar progressBar = progressBoxComponent.createProgressBar();
    Label progressLabel = progressBoxComponent.createProgressLabel();
    VBox progressBox = progressBoxComponent.createProgressBox(progressBar, progressLabel, scene);
    ToolBar toolBar = buttonsComponent.getToolBar(primaryStage, progressBar, progressLabel);
    HBox toolsBox = tableViewComponent.creatToolsBox();
    ReadOnlyBooleanProperty jdkDownloadRunning = buttonsComponent.jdkDownloadRunningProperty();

    toolsBox.disableProperty().bind(jdkDownloadRunning);
    treeTableView.disableProperty().bind(jdkDownloadRunning);

    VBox vbox = new VBox(10);
    vbox.getChildren().addAll(toolBar, toolsBox, progressBox, treeTableView);
    VBox.setVgrow(treeTableView, Priority.ALWAYS);

    root.setCenter(vbox);

    // Configure MenuBar
    MenuBar menuBar = menuComponent.getMenuBar(primaryStage);
    menuBar.disableProperty().bind(jdkDownloadRunning);
    root.setTop(menuBar);

    Label developerLabel = new Label("Developed by Rusen Stefan @ Oracle");
    BorderPane.setAlignment(developerLabel, Pos.BOTTOM_CENTER);
    root.setBottom(developerLabel);
    primaryStage.setTitle("Dependency Analyzer");
    primaryStage.setOnShown(event -> {
      Platform.setImplicitExit(true);
      log.info("Main JavaFX stage shown");
      startupProbe("Main JavaFX stage onShown fired");
    });
    URL stylesheetUrl = getClass().getResource("/styles.css");
    if (stylesheetUrl != null) {
      scene.getStylesheets().add(stylesheetUrl.toExternalForm());
    }
    primaryStage.setScene(scene);
    log.info("Calling primaryStage.show()");
    startupProbe("Calling primaryStage.show()");
    primaryStage.show();
    primaryStage.toFront();
    primaryStage.requestFocus();
  }

  private static void startupProbe(String message) {
    String line = "[startup] " + message + System.lineSeparator();
    try {
      log.info("[startup] {}", message);
    } catch (Throwable loggingFailure) {
      System.err.print(line);
    }
    try {
      Path parent = STARTUP_TRACE_FILE.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(STARTUP_TRACE_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      try {
        log.warn("[startup] Failed writing startup trace file: {}", e.getMessage());
      } catch (Throwable ignored) {
        System.err.print(line);
        System.err.println("[startup] Failed writing startup trace file: " + e.getMessage());
      }
    }
  }
}

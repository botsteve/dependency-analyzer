package io.botsteve.dependencyanalyzer.views;


import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
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
import java.util.logging.Level;
import io.botsteve.dependencyanalyzer.components.ButtonsComponent;
import io.botsteve.dependencyanalyzer.components.CheckBoxComponent;
import io.botsteve.dependencyanalyzer.components.ColumnsComponent;
import io.botsteve.dependencyanalyzer.components.MenuComponent;
import io.botsteve.dependencyanalyzer.components.ProgressBoxComponent;
import io.botsteve.dependencyanalyzer.components.TableViewComponent;

@Data
public class MainAppView {

  private static final Logger log = LoggerFactory.getLogger(MainAppView.class);
  private static final java.util.logging.Logger JUL_LOG = java.util.logging.Logger.getLogger(MainAppView.class.getName());
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
    var treeTableView = tableViewComponent.getTreeTableView();
    var dependencyColumn = columnsComponent.getDependencyTreeTableColumn();
    var scopeColumn = columnsComponent.getScopeColumn();
    var scmColumn = columnsComponent.getSCMTreeTableColumn();
    var selectColumn = columnsComponent.getSelectTreeTableColumn();
    var checkoutTagColumn = columnsComponent.getCheckoutTagColumn();
    var buildWithColumn = columnsComponent.getBuildWithColumn();
    treeTableView.getColumns().addAll(selectColumn, dependencyColumn, scopeColumn, scmColumn, checkoutTagColumn, buildWithColumn);
    treeTableView.setTreeColumn(dependencyColumn);
    columnsComponent.configureColumnsWidthStyle(selectColumn, dependencyColumn, scopeColumn, scmColumn, checkoutTagColumn, buildWithColumn);
    checkBoxComponent.configureCheckBoxAction();

    var progressBar = progressBoxComponent.createProgressBar();
    var progressLabel = progressBoxComponent.createProgressLabel();
    var progressBox = progressBoxComponent.createProgressBox(progressBar, progressLabel, scene);
    var toolBar = buttonsComponent.getToolBar(primaryStage, progressBar, progressLabel);
    var toolsBox = tableViewComponent.creatToolsBox();
    var jdkDownloadRunning = buttonsComponent.jdkDownloadRunningProperty();

    toolsBox.disableProperty().bind(jdkDownloadRunning);
    treeTableView.disableProperty().bind(jdkDownloadRunning);

    VBox vbox = new VBox(10);
    vbox.getChildren().addAll(toolBar, toolsBox, progressBox, treeTableView);
    VBox.setVgrow(treeTableView, Priority.ALWAYS);

    root.setCenter(vbox);

    // Configure MenuBar
    var menuBar = menuComponent.getMenuBar(primaryStage);
    menuBar.disableProperty().bind(jdkDownloadRunning);
    root.setTop(menuBar);

    Label developerLabel = new Label("Developed by Rusen Stefan @ Oracle");
    BorderPane.setAlignment(developerLabel, Pos.BOTTOM_CENTER);
    root.setBottom(developerLabel);
    primaryStage.setTitle("Dependency Analyzer");
    primaryStage.setOnShown(event -> {
      javafx.application.Platform.setImplicitExit(true);
      log.info("Main JavaFX stage shown");
      JUL_LOG.log(Level.INFO, "Main JavaFX stage shown");
      startupProbe("Main JavaFX stage onShown fired");
    });
    var stylesheetUrl = getClass().getResource("/styles.css");
    if (stylesheetUrl != null) {
      scene.getStylesheets().add(stylesheetUrl.toExternalForm());
    }
    primaryStage.setScene(scene);
    log.info("Calling primaryStage.show()");
    JUL_LOG.log(Level.INFO, "Calling primaryStage.show()");
    startupProbe("Calling primaryStage.show()");
    primaryStage.show();
    primaryStage.toFront();
    primaryStage.requestFocus();
  }

  private static void startupProbe(String message) {
    String line = "[startup] " + message + System.lineSeparator();
    System.err.print(line);
    try {
      Files.writeString(STARTUP_TRACE_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      System.err.println("[startup] Failed writing startup trace file: " + e.getMessage());
    }
  }
}

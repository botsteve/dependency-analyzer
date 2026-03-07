package io.botsteve.dependencyanalyzer.components;

import static io.botsteve.dependencyanalyzer.utils.Utils.loadSettings;
import static io.botsteve.dependencyanalyzer.utils.Utils.saveSettings;

import java.util.Properties;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.Data;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.stage.FileChooser;
import java.io.IOException;
import javafx.scene.control.CheckMenuItem;
import io.botsteve.dependencyanalyzer.utils.FxUtils;
import io.botsteve.dependencyanalyzer.model.EnvSetting;
import java.io.File;

@Data
public class MenuComponent { 

  private TableViewComponent tableViewComponent;

  public MenuComponent(TableViewComponent tableViewComponent) {
    this.tableViewComponent = tableViewComponent;
  }

  public static final String ABOUT_TEXT = """
          Dependency Analyzer is a JavaFX desktop tool for Maven and Gradle dependency workflows.

          Current capabilities:
              - Analyze Maven (pom.xml) and Gradle (build.gradle/build.gradle.kts) dependency trees.
              - View dependency scope, apply fuzzy/exclude/scope filters, and select dependencies from the tree.
              - Resolve SCM/source repository URLs using metadata and runtime SCM override mappings.
              - Download selected 3rd-party and 4th-party source repositories and checkout matching tags.
              - Build selected downloaded 3rd-party dependencies with Maven/Gradle/Ant using configured JDK toolchains (8/11/17/21).
              - Package a GraalVM native executable with JavaFX metadata support from the Maven native profile.
              - Auto-download required JDKs (8/11/17/21) for the current OS/architecture and update config/env-settings.properties.
              - Show live JDK download/extraction progress in the UI and in logs.
              - Temporarily lock the rest of the UI while JDK bootstrap is running to avoid conflicting actions.
              - Generate aggregated license reports for downloaded dependency repositories.

          Environment requirements:
              - JAVA_HOME pointing to JDK 21+.
              - MAVEN_HOME pointing to a local Maven installation.
              - For native builds: GraalVM JDK 21+ with native-image installed and GRAALVM_HOME configured.
              - JAVA8_HOME, JAVA11_HOME, JAVA17_HOME, JAVA21_HOME configured in Settings → Environment Settings,
                or use the "Download Required JDKs" button for automatic setup.
              - Git available on PATH.
              - Proxy support via https_proxy/HTTPS_PROXY (fallback http_proxy/HTTP_PROXY), with bypass via no_proxy/NO_PROXY.
       """;

  /**
   * Opens the environment settings dialog and persists user edits.
   */
  public void openSettingsDialog(Stage primaryStage) {
    // Load existing settings or create new ones
    Properties settings = loadSettings();

    Dialog<Void> dialog = new Dialog<>();
    dialog.setTitle("Environment Settings");
    dialog.initOwner(primaryStage);
    dialog.initModality(Modality.APPLICATION_MODAL);

    VBox content = new VBox(10);
    content.setStyle("-fx-padding: 20;");

    // Helper to create rows
    addSettingRow(content, settings, "JAVA21_HOME", primaryStage);
    addSettingRow(content, settings, "JAVA17_HOME", primaryStage);
    addSettingRow(content, settings, "JAVA11_HOME", primaryStage);
    addSettingRow(content, settings, "JAVA8_HOME", primaryStage);

    ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

    dialog.getDialogPane().setContent(content);

    dialog.setResultConverter(dialogButton -> {
      if (dialogButton == saveButtonType) {
        ObservableList<EnvSetting> settingsList = FXCollections.observableArrayList();
        content.getChildren().forEach(node -> {
            if (node instanceof javafx.scene.layout.HBox) {
                javafx.scene.layout.HBox row = (javafx.scene.layout.HBox) node;
                String key = ((javafx.scene.control.Label) row.getChildren().get(0)).getText();
                String value = ((javafx.scene.control.TextField) row.getChildren().get(1)).getText();
                settingsList.add(new EnvSetting(new SimpleStringProperty(key), new SimpleStringProperty(value)));
            }
        });
        saveSettings(settingsList);
      }
      return null;
    });

    dialog.showAndWait();
  }

  private void addSettingRow(VBox parent, Properties settings, String key, Stage stage) {
      javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(10);
      row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
      
      javafx.scene.control.Label label = new javafx.scene.control.Label(key);
      label.setMinWidth(100);
      
      javafx.scene.control.TextField textField = new javafx.scene.control.TextField(settings.getProperty(key, ""));
      textField.setPrefWidth(300);
      
      Button browseButton = new Button("Browse");
      browseButton.setOnAction(e -> {
          javafx.stage.DirectoryChooser directoryChooser = new javafx.stage.DirectoryChooser();
          directoryChooser.setTitle("Select " + key);
          File selectedDirectory = directoryChooser.showDialog(stage);
          if (selectedDirectory != null) {
              textField.setText(selectedDirectory.getAbsolutePath());
          }
      });
      
      row.getChildren().addAll(label, textField, browseButton);
      parent.getChildren().add(row);
  }

  private void openAboutDialog() {
    Dialog<Void> dialog = new Dialog<>();
    dialog.setTitle("About Project");
    dialog.initModality(Modality.APPLICATION_MODAL);

    TextArea textArea = new TextArea(ABOUT_TEXT);
    textArea.setEditable(false);
    textArea.setWrapText(true);

    VBox dialogVBox = new VBox(textArea);
    dialogVBox.setPrefSize(600, 400);

    // Set the TextArea to fill the VBox
    VBox.setVgrow(textArea, Priority.ALWAYS);
    textArea.setMaxHeight(Double.MAX_VALUE);
    textArea.setMaxWidth(Double.MAX_VALUE);

    dialog.getDialogPane().setContent(dialogVBox);

    ButtonType okButtonType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().add(okButtonType);

    dialog.showAndWait();
  }


  /**
   * Builds the About menu section.
   */
  public Menu getAboutMenu() {
    Menu about = new Menu("About");
    MenuItem readMe = new MenuItem("Read Me");
    readMe.setOnAction(e -> openAboutDialog());
    about.getItems().add(readMe);
    return about;
  }

  /**
   * Builds the Settings menu section.
   */
  public Menu getSettingsMenu(Stage primaryStage) {
    Menu settingsMenu = new Menu("Settings");
    MenuItem envSettingsItem = new MenuItem("Environment Settings");
    envSettingsItem.setOnAction(event -> openSettingsDialog(primaryStage));
    settingsMenu.getItems().add(envSettingsItem);
    return settingsMenu;
  }

  /**
   * Builds the File menu section.
   */
  public Menu getFileMenu(Stage primaryStage) {
    Menu fileMenu = new Menu("File");
    MenuItem exportItem = new MenuItem("Export to JSON");
    exportItem.setOnAction(event -> exportDependencies(primaryStage));
    fileMenu.getItems().add(exportItem);
    return fileMenu;
  }

  /**
   * Builds the View menu section.
   */
  public Menu getViewMenu(Stage primaryStage) {
    Menu viewMenu = new Menu("View");
    CheckMenuItem darkModeItem = new CheckMenuItem("Dark Mode");
    darkModeItem.setOnAction(event -> {
      Scene scene = primaryStage.getScene();
      if (scene != null) {
        if (darkModeItem.isSelected()) {
          scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
          scene.getRoot().getStyleClass().add("dark-mode");
        } else {
          scene.getRoot().getStyleClass().remove("dark-mode");
        }
      }
    });
    viewMenu.getItems().add(darkModeItem);
    return viewMenu;
  }

  private void exportDependencies(Stage primaryStage) {
     FileChooser fileChooser = new FileChooser();
     fileChooser.setTitle("Save Dependencies");
     fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
     File file = fileChooser.showSaveDialog(primaryStage);
     if (file != null) {
         try {
             ObjectMapper mapper = new ObjectMapper();
             mapper.writerWithDefaultPrettyPrinter().writeValue(file, tableViewComponent.getAllDependencies());
             FxUtils.showAlert("Dependencies exported successfully!");
         } catch (IOException e) {
             FxUtils.showError("Failed to export dependencies: " + e.getMessage());
         }
     }
  }

  /**
   * Builds and returns the top-level menu bar for the application shell.
   */
  public MenuBar getMenuBar(Stage primaryStage) {
    MenuBar menuBar = new MenuBar();
    var fileMenu = getFileMenu(primaryStage);
    var settingsMenu = getSettingsMenu(primaryStage);
    var viewMenu = getViewMenu(primaryStage);
    var aboutMenu = getAboutMenu();

    menuBar.getMenus().addAll(fileMenu, settingsMenu, viewMenu, aboutMenu);
    return menuBar;
  }
}

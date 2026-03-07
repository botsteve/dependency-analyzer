package io.botsteve.dependencyanalyzer.utils;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FxUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(FxUtils.class);

  public static HBox createBox(HBox box, Pos position) {
    box.setAlignment(position);
    return box;
  }

  public static void showAlert(String message) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    Label label = new Label(message);
    label.setWrapText(true);
    alert.getDialogPane().setContent(label);
    alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
    alert.setTitle("INFO");
    alert.setHeaderText("");
    alert.showAndWait();
  }

  public static void showError(String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    Label label = new Label(message);
    label.setWrapText(true);
    alert.getDialogPane().setContent(label);
    alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
    alert.setTitle("ERROR");
    alert.setHeaderText("");
    alert.showAndWait();
  }

  public static void showTextDialog(String title, String header, String message) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    TextArea textArea = new TextArea(message == null ? "" : message);
    textArea.setEditable(false);
    textArea.setWrapText(true);
    textArea.setPrefRowCount(18);
    textArea.setPrefColumnCount(110);
    textArea.setMaxWidth(Double.MAX_VALUE);
    textArea.setMaxHeight(Double.MAX_VALUE);

    alert.getDialogPane().setContent(textArea);
    alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
    alert.getDialogPane().setPrefWidth(900);
    alert.setTitle(title);
    alert.setHeaderText(header);
    alert.showAndWait();
  }

  public static void getErrorAlertAndCloseProgressBar(String message,
                                                      ProgressBar progressBar,
                                                      Label progressLabel) {
    Platform.runLater(() -> {
      LOGGER.debug("Closing progress bar and clear label");
      progressBar.setVisible(false);
      progressLabel.setVisible(false);
      showError(message);
    });
  }

  public static void updateProgressBarAndLabel(String message, ProgressBar progressBar, Label progressLabel) {
    Platform.runLater(() -> {
      LOGGER.debug("Enable progress bar and configure label message: {}", message);
      progressBar.setVisible(true);
      progressLabel.setVisible(true);
      if (progressLabel.textProperty().isBound()) {
        LOGGER.debug("Progress label text is bound; skipping direct text assignment");
      } else {
        progressLabel.setText(message);
      }
    });
  }
}

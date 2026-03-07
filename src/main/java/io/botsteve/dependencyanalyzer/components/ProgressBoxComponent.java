package io.botsteve.dependencyanalyzer.components;



import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

public class ProgressBoxComponent {

  public Label createProgressLabel() {
    Label progressLabel = new Label();
    progressLabel.setVisible(false);
    return progressLabel;
  }

  public ProgressBar createProgressBar() {
    ProgressBar progressBar = new ProgressBar();
    progressBar.setVisible(false);
    return progressBar;
  }

  public VBox createProgressBox(ProgressBar progressBar, Label progressLabel, Scene scene) {
    progressBar.getStyleClass().add("major-progress-bar");
    progressLabel.getStyleClass().add("major-progress-label");
    
    progressBar.managedProperty().bind(progressBar.visibleProperty());
    progressLabel.managedProperty().bind(progressLabel.visibleProperty());
    
    VBox progressBox = new VBox(5, progressLabel, progressBar);
    progressBox.setAlignment(Pos.CENTER);
    progressBox.setPadding(new javafx.geometry.Insets(10, 20, 10, 20));
    progressBox.setMaxWidth(Double.MAX_VALUE);
    progressBox.prefWidthProperty().bind(scene.widthProperty());

    progressBar.setMaxWidth(Double.MAX_VALUE);
    progressBar.setPrefHeight(20); // Clearly thicker
    progressBar.prefWidthProperty().bind(progressBox.widthProperty().multiply(0.9));
    return progressBox;
  }
}

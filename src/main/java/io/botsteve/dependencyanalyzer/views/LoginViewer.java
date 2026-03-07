package io.botsteve.dependencyanalyzer.views;


import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.Data;

import static io.botsteve.dependencyanalyzer.utils.FxUtils.showAlert;

@Data
public class LoginViewer {

  public static final LoginViewer LOGIN_VIEWER = new LoginViewer();
  public boolean skip = true;
  private MainAppView mainAppView = new MainAppView();

  public void showPasswordDialog(Stage primaryStage) {
    Stage passwordStage = new Stage();
    VBox passwordVBox = new VBox(10);
    passwordVBox.setAlignment(Pos.CENTER);

    Label passwordLabel = new Label("Password:");
    PasswordField passwordField = new PasswordField();
    Button loginButton = new Button("Login");

    loginButton.setOnAction(event -> {
      String password = passwordField.getText();
      if (validatePassword(password)) {
        passwordStage.close();
        mainAppView.start(primaryStage);
      } else {
        showAlert("Invalid password. Please try again.");
      }
    });

    passwordVBox.getChildren().addAll(passwordLabel, passwordField, loginButton);
    Scene passwordScene = new Scene(passwordVBox, 300, 150);
    passwordStage.setScene(passwordScene);
    passwordStage.setTitle("Password");
    if (skip) {
      passwordStage.close();
      mainAppView.start(primaryStage);
    } else {
      passwordStage.show();
    }
  }

  private static boolean validatePassword(String password) {
    return "SteveCGIU".equals(password);
  }
}

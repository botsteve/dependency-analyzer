package io.botsteve.dependencyanalyzer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.beans.property.SimpleStringProperty;
import org.junit.jupiter.api.Test;

class EnvSettingTest {

  @Test
  void shouldExposeAndMutateJavaFxProperties() {
    EnvSetting setting = new EnvSetting(new SimpleStringProperty("JAVA17_HOME"), new SimpleStringProperty("/tmp/jdk17"));
    setting.setName("JAVA21_HOME");
    setting.setValue("/tmp/jdk21");

    assertEquals("JAVA21_HOME", setting.getName());
    assertEquals("/tmp/jdk21", setting.getValue());
    assertEquals("JAVA21_HOME", setting.nameProperty().get());
    assertEquals("/tmp/jdk21", setting.valueProperty().get());
  }
}

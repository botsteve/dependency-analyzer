package io.botsteve.dependencyanalyzer.model;

import java.io.File;

/**
 * Represents the type of build system used by a project.
 */
public enum ProjectType {
  MAVEN,
  GRADLE,
  UNKNOWN;

  /**
   * Detects the project type based on the files present in the project directory.
   */
  public static ProjectType detect(File projectDir) {
    if (new File(projectDir, "pom.xml").exists()) {
      return MAVEN;
    } else if (new File(projectDir, "build.gradle").exists()
               || new File(projectDir, "build.gradle.kts").exists()
               || new File(projectDir, "settings.gradle").exists()
               || new File(projectDir, "settings.gradle.kts").exists()) {
      return GRADLE;
    }
    return UNKNOWN;
  }
}

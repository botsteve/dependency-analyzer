package io.botsteve.dependencyanalyzer.model;

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
  public static ProjectType detect(java.io.File projectDir) {
    if (new java.io.File(projectDir, "pom.xml").exists()) {
      return MAVEN;
    } else if (new java.io.File(projectDir, "build.gradle").exists()
               || new java.io.File(projectDir, "build.gradle.kts").exists()
               || new java.io.File(projectDir, "settings.gradle").exists()
               || new java.io.File(projectDir, "settings.gradle.kts").exists()) {
      return GRADLE;
    }
    return UNKNOWN;
  }
}

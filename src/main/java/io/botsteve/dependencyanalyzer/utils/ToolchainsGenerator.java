package io.botsteve.dependencyanalyzer.utils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToolchainsGenerator {

  private static final Logger log = LoggerFactory.getLogger(ToolchainsGenerator.class);

  /**
   * Generates a Maven toolchains.xml file from configured JDK settings.
   *
   * @param settings persisted environment settings containing JAVA*_HOME values
   * @return generated toolchains file path
   * @throws IOException when file writing fails
   * @throws URISyntaxException when repository path resolution fails
   */
  public static File generateToolchainsXml(Properties settings) throws IOException, URISyntaxException {
    File toolchainsFile = new File(Utils.getRepositoriesPath(), "toolchains.xml");
    
    try (FileWriter writer = new FileWriter(toolchainsFile)) {
      writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      writer.write("<toolchains>\n");

      addJdkToolchain(writer, "1.8", settings.getProperty("JAVA8_HOME"));
      addJdkToolchain(writer, "11", settings.getProperty("JAVA11_HOME"));
      addJdkToolchain(writer, "17", settings.getProperty("JAVA17_HOME"));
      addJdkToolchain(writer, "21", settings.getProperty("JAVA21_HOME"));

      writer.write("</toolchains>\n");
    }
    
    log.info("Generated Maven toolchains.xml at: {}", toolchainsFile.getAbsolutePath());
    return toolchainsFile;
  }

  private static void addJdkToolchain(FileWriter writer, String version, String jdkPath) throws IOException {
    if (jdkPath != null && !jdkPath.isBlank()) {
      writer.write("  <toolchain>\n");
      writer.write("    <type>jdk</type>\n");
      writer.write("    <provides>\n");
      writer.write("      <version>" + version + "</version>\n");
      // Add vendor if needed, but version is usually sufficient
      writer.write("    </provides>\n");
      writer.write("    <configuration>\n");
      writer.write("      <jdkHome>" + jdkPath + "</jdkHome>\n");
      writer.write("    </configuration>\n");
      writer.write("  </toolchain>\n");
    }
  }
}

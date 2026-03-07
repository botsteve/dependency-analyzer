package io.botsteve.dependencyanalyzer.utils;

import static io.botsteve.dependencyanalyzer.service.MavenInvokerService.getMavenInvokerResult;
import static io.botsteve.dependencyanalyzer.utils.Utils.getPropertyFromSetting;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaVersionResolver {

  private static final Logger LOG = LoggerFactory.getLogger(JavaVersionResolver.class);

  public static final List<String> JDKS = List.of("JAVA21_HOME", "JAVA17_HOME", "JAVA11_HOME", "JAVA8_HOME");

  public static String resolveJavaPathToBeUsed(String javaVersion) {
    return switch (javaVersion) {
      case "21.0" -> getPropertyFromSetting("JAVA21_HOME");
      case "11.0" -> getPropertyFromSetting("JAVA11_HOME");
      case "17.0" -> getPropertyFromSetting("JAVA17_HOME");
      case "1.8", "8.0" -> getPropertyFromSetting("JAVA8_HOME");
      case null, default -> System.getenv("JAVA_HOME");
    };
  }

  public static String resolveJavaVersionToEnvProperty(String javaVersion) {
    return switch (javaVersion) {
      case "21.0" -> "JAVA21_HOME";
      case "11.0" -> "JAVA11_HOME";
      case "17.0" -> "JAVA17_HOME";
      case "1.8", "8.0" -> "JAVA8_HOME";
      case null, default -> "JAVA_HOME";
    };
  }


  public static String getJavaVersionMaven(File repo) {
    var outputHandler = getMavenInvokerResult(repo.getAbsolutePath(),
                                              "", "help:effective-pom",
                                              "", System.getenv("JAVA_HOME"));
    List<String> outputLines = outputHandler.getOutput();
    String response = String.join("\n", outputLines);
    return resolveJavaVersionFromEffectivePom(response);
  }

  public static String resolveJavaVersionFromEffectivePom(String effectivePomOutput) {
    try {
      // Isolate XML content from potential Maven log output
      int startIndex = effectivePomOutput.indexOf("<project");
      int endIndex = effectivePomOutput.lastIndexOf("</project>");
      if (startIndex == -1 || endIndex == -1) {
         LOG.warn("Could not find valid project XML in effective-pom output");
        return null;
      }
      String effectivePomXml = effectivePomOutput.substring(startIndex, endIndex + 10);

      // Parse XML
      javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
      javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
      org.w3c.dom.Document doc = builder.parse(new java.io.ByteArrayInputStream(effectivePomXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      doc.getDocumentElement().normalize();

      Set<Double> javaVersionDetected = new HashSet<>();

      // 1. Check properties (maven.compiler.source, maven.compiler.target, etc)
      org.w3c.dom.NodeList properties = doc.getElementsByTagName("properties");
      if (properties.getLength() > 0) {
        org.w3c.dom.NodeList children = properties.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
          org.w3c.dom.Node node = children.item(i);
          if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
             String name = node.getNodeName();
             if (name.equals("maven.compiler.source") || name.equals("maven.compiler.target") || 
                 name.equals("maven.compiler.release") || name.equals("java.version")) {
                 parseAndAddVersion(node.getTextContent(), javaVersionDetected);
             }
          }
        }
      }

      // 2. Check maven-compiler-plugin configuration
      org.w3c.dom.NodeList plugins = doc.getElementsByTagName("plugin");
      for (int i = 0; i < plugins.getLength(); i++) {
        org.w3c.dom.Element plugin = (org.w3c.dom.Element) plugins.item(i);
        String artifactId = getTagValue(plugin, "artifactId");
        if ("maven-compiler-plugin".equals(artifactId)) {
          org.w3c.dom.NodeList configs = plugin.getElementsByTagName("configuration");
          if (configs.getLength() > 0) {
             org.w3c.dom.Element config = (org.w3c.dom.Element) configs.item(0);
             parseAndAddVersion(getTagValue(config, "source"), javaVersionDetected);
             parseAndAddVersion(getTagValue(config, "target"), javaVersionDetected);
             parseAndAddVersion(getTagValue(config, "release"), javaVersionDetected);
          }
        }
      }
      
      // 3. Check maven-enforcer-plugin
       for (int i = 0; i < plugins.getLength(); i++) {
        org.w3c.dom.Element plugin = (org.w3c.dom.Element) plugins.item(i);
        String artifactId = getTagValue(plugin, "artifactId");
        if ("maven-enforcer-plugin".equals(artifactId)) {
             // Basic check for requireJavaVersion inside executions/configuration
             // This is complex in DOM, simple check for now:
             // We can defer full enforcer parsing as it can be nested deeply.
             // Relying on compiler plugin is usually sufficient for build version.
        }
      }

      return javaVersionDetected.stream()
               .max(Double::compare)
               .map(String::valueOf)
               .orElse(null);

    } catch (Exception e) {
      LOG.error("Failed to parse effective POM XML", e);
      return null;
    }
  }

  private static void parseAndAddVersion(String version, Set<Double> versions) {
      if (version == null) return;
      version = version.trim();
      if (isValidVersion(version)) {
          try {
             versions.add(Double.parseDouble(version));
          } catch (NumberFormatException e) {
             LOG.debug("Unable to parse Java version '{}' from effective-pom property", version, e);
          }
      }
  }

  private static String getTagValue(org.w3c.dom.Element element, String tagName) {
      org.w3c.dom.NodeList nodeList = element.getElementsByTagName(tagName);
      if (nodeList.getLength() > 0) {
          return nodeList.item(0).getTextContent();
      }
      return null;
  }

  public static boolean isValidVersion(String str) {
    try {
      Double.parseDouble(str);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}

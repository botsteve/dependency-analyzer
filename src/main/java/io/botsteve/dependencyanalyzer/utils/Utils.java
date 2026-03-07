package io.botsteve.dependencyanalyzer.utils;

import javafx.collections.ObservableList;
import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.model.EnvSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.math.BigInteger;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.botsteve.dependencyanalyzer.utils.FxUtils.showError;

public class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public static final String DOWNLOADED_REPOS = "downloaded_repos";
    public static final String THIRD_PARTY_REPOS = "3rd-party";
    public static final String FOURTH_PARTY_REPOS = "4th-party";
    public static final String DOWNLOADED_JDKS = "downloaded_jdks";
    public static final String CONFIG_DIRECTORY = "config";
    public static final String SETTINGS_FILE_PATH = CONFIG_DIRECTORY + "/env-settings.properties";
    public static final String LEGACY_SETTINGS_FILE_PATH = "env-settings.properties";
    public static final List<String> REQUIRED_JDK_SETTINGS = List.of("JAVA8_HOME", "JAVA11_HOME", "JAVA17_HOME", "JAVA21_HOME");

    /**
     * Parses module declarations from a Maven parent POM.
     *
     * @param pomFile root pom.xml file
     * @return ordered module names declared under {@code <modules>}
     * @throws Exception when XML parsing fails
     */
    public static List<String> parseModulesFromPom(File pomFile) throws Exception {
      List<String> modules = new ArrayList<>();

      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document doc = dBuilder.parse(pomFile);
        doc.getDocumentElement().normalize();

      NodeList nodeList = doc.getElementsByTagName("module");
        for (int i = 0; i < nodeList.getLength(); i++) {
            modules.add(nodeList.item(i).getTextContent());
        }

        return modules;
    }


    /**
     * Reads the project display name from a POM file.
     *
     * @param pomFile pom.xml file
     * @return project name content from the {@code <name>} element
     * @throws Exception when XML parsing fails
     */
    public static String getProjectName(File pomFile) throws Exception {

      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document doc = dBuilder.parse(pomFile);
        doc.getDocumentElement().normalize();

      NodeList nodeList = doc.getElementsByTagName("name");
        return nodeList.item(0).getTextContent();
    }

    /**
     * Returns a configured environment property from persisted settings.
     *
     * @param property setting key (for example {@code JAVA17_HOME})
     * @return configured value or empty string when absent
     */
    public static String getPropertyFromSetting(String property) {
      Properties properties = loadSettings();
        return properties.getProperty(property, "");
    }

    /**
     * Loads environment settings from {@code config/env-settings.properties}.
     *
     * @return loaded settings
     */
    public static Properties loadSettings() {
      Properties properties = new Properties();
      Path settingsPath = resolveSettingsPath();
        migrateLegacySettingsFileIfNeeded(settingsPath);
        try (InputStream is = new FileInputStream(settingsPath.toFile())) {
            properties.load(is);
        } catch (IOException e) {
            log.error("Failed loading settings from {}", settingsPath, e);
            throw new DependencyAnalyzerException(e);
        }
        return properties;
    }

    /**
     * Persists environment settings from UI model rows.
     *
     * @param settingsList settings table rows
     */
    public static void saveSettings(ObservableList<EnvSetting> settingsList) {
      Properties properties = new Properties();
        settingsList.forEach(setting -> properties.setProperty(setting.getName(), setting.getValue()));

        saveSettings(properties);
    }

    /**
     * Persists environment settings into the canonical settings file.
     *
     * @param properties properties to save
     */
    public static void saveSettings(Properties properties) {
      Properties safeProperties = properties == null ? new Properties() : properties;
      Path settingsPath = resolveSettingsPath();
        migrateLegacySettingsFileIfNeeded(settingsPath);
        ensureConfigDirectoryExists(settingsPath);

        try (OutputStream os = new FileOutputStream(settingsPath.toFile())) {
            safeProperties.store(os, "Environment Settings");
            log.info("Settings saved to {}", settingsPath);
        } catch (IOException e) {
            log.error("Failed saving settings to {}", settingsPath, e);
            throw new DependencyAnalyzerException(e);
        }
    }

    /**
     * Validates required JDK settings and displays user-facing validation errors.
     *
     * @return {@code true} when required settings exist and point to valid JDK homes
     */
    public static boolean arePropertiesConfiguredAndValid() {
      List<String> missingProperties = getMissingJdkSettings();
        if (!missingProperties.isEmpty()) {
            showError("""
                    Dependencies might be targeted for compilation with different JDK versions.
                    Please configure or download all required JDK settings: %s
                    (Settings -> Environment Settings or use 'Download Required JDKs').
                    """.formatted(String.join(", ", missingProperties)));
            return false;
        }

      List<String> invalidProperties = getInvalidJdkSettings();
        if (!invalidProperties.isEmpty()) {
            showError("""
                    The following JDK settings are invalid: %s
                    Each setting should point to the root directory path of a JDK.
                    """.formatted(String.join(", ", invalidProperties)));
            return false;
        }
        return true;
    }

    /**
     * Returns required JDK setting keys that are missing values.
     */
    public static List<String> getMissingJdkSettings() {
      Properties properties = loadSettings();
        return REQUIRED_JDK_SETTINGS.stream()
            .filter(key -> properties.getProperty(key, "").isBlank())
            .toList();
    }

    /**
     * Returns required JDK setting keys that point to invalid directories.
     */
    public static List<String> getInvalidJdkSettings() {
      Properties properties = loadSettings();
        return REQUIRED_JDK_SETTINGS.stream()
            .filter(key -> {
              String value = properties.getProperty(key, "").trim();
                return !value.isBlank() && isInvalidJdkHome(new File(value));
            })
            .toList();
    }

    /**
     * Returns required JDK setting keys that are missing or invalid.
     */
    public static List<String> getMissingOrInvalidJdkSettings() {
      LinkedHashSet<String> keys = new LinkedHashSet<>(getMissingJdkSettings());
        keys.addAll(getInvalidJdkSettings());
        return new ArrayList<>(keys);
    }

    /**
     * Creates the settings file if it does not already exist.
     */
    public static void createSettingsFile() {
      Path settingsPath = resolveSettingsPath();
        migrateLegacySettingsFileIfNeeded(settingsPath);
        ensureConfigDirectoryExists(settingsPath);

      File file = settingsPath.toFile();
        if (!file.exists()) {
            try {
              Properties properties = new Properties();
                file.createNewFile();

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    properties.store(fos, "Environment Settings");
                    log.info("Properties file created successfully at {}", settingsPath);
                } catch (IOException e) {
                    log.error("Failed creating settings file at {}", settingsPath, e);
                    throw new DependencyAnalyzerException(e);
                }
            } catch (IOException e) {
                log.error("Failed initializing settings file at {}", settingsPath, e);
                throw new DependencyAnalyzerException(e);
            }
        } else {
            log.info("Properties file already exists at {}", settingsPath);
        }
    }

    private static Path resolveSettingsPath() {
      String baseDir = System.getProperty(LogUtils.BASE_DIR_PROPERTY, System.getProperty("user.dir"));
        return Path.of(baseDir).resolve(SETTINGS_FILE_PATH).toAbsolutePath().normalize();
    }

    private static Path resolveLegacySettingsPath() {
      String baseDir = System.getProperty(LogUtils.BASE_DIR_PROPERTY, System.getProperty("user.dir"));
        return Path.of(baseDir).resolve(LEGACY_SETTINGS_FILE_PATH).toAbsolutePath().normalize();
    }

    private static void ensureConfigDirectoryExists(Path settingsPath) {
      Path parent = settingsPath.getParent();
        if (parent == null) {
            return;
        }
        try {
            if (!Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new DependencyAnalyzerException("Failed to create settings directory: " + parent, e);
        }
    }

    private static void migrateLegacySettingsFileIfNeeded(Path settingsPath) {
      Path legacySettingsPath = resolveLegacySettingsPath();
        if (!Files.exists(legacySettingsPath) || Files.exists(settingsPath)) {
            return;
        }
        ensureConfigDirectoryExists(settingsPath);
        try {
            Files.move(legacySettingsPath, settingsPath);
            log.info("Migrated legacy settings file from {} to {}", legacySettingsPath, settingsPath);
        } catch (IOException e) {
            throw new DependencyAnalyzerException(
                "Failed to migrate legacy settings file from " + legacySettingsPath + " to " + settingsPath, e);
        }
    }

    /**
     * Resolves the base repository directory used by download/build workflows.
     *
     * @param projectName optional project subdirectory name
     * @return absolute repositories directory path
     */
    public static String getRepositoriesPath(String projectName) {
        try {
          Path codeSourcePath = Paths.get(Utils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path baseDir;
            
            if (codeSourcePath.toString().endsWith(".jar")) {
                baseDir = codeSourcePath.getParent();
            } else {
                // Assuming .../target/classes
                baseDir = codeSourcePath.getParent().getParent(); 
                if (baseDir == null) baseDir = Paths.get("."); // Fallback
            }
            
            Path repoDir = baseDir.resolve(DOWNLOADED_REPOS);
            if (projectName != null && !projectName.isEmpty()) {
                repoDir = repoDir.resolve(projectName);
            }
            
            if (!Files.exists(repoDir)) {
                 Files.createDirectories(repoDir);
            }
            return repoDir.toAbsolutePath().toString();
        } catch (Exception e) {
             throw new RuntimeException("Failed to resolve repositories path", e);
        }
    }

    /**
     * Resolves the base repository directory without project scoping.
     */
    public static String getRepositoriesPath() {
        return getRepositoriesPath(null);
    }

    /**
     * Resolves and creates the third-party repository directory for a project.
     */
    public static String getThirdPartyRepositoriesPath(String projectName) {
        return ensureDirectory(Path.of(getRepositoriesPath(projectName), THIRD_PARTY_REPOS)).toString();
    }

    /**
     * Resolves and creates the fourth-party repository root for a project.
     */
    public static String getFourthPartyRepositoriesPath(String projectName) {
        return ensureDirectory(Path.of(getRepositoriesPath(projectName), FOURTH_PARTY_REPOS)).toString();
    }

    /**
     * Resolves and creates the fourth-party directory for one third-party parent repository.
     */
    public static String getFourthPartyRepositoriesPath(String projectName, String thirdPartyRepoName) {
        String parentName = sanitizeDirectoryName(thirdPartyRepoName);
        if (parentName.isBlank()) {
            parentName = "unknown-third-party";
        }
        return ensureDirectory(Path.of(getFourthPartyRepositoriesPath(projectName), parentName)).toString();
    }

    private static Path ensureDirectory(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            return path.toAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + path, e);
        }
    }

    private static String sanitizeDirectoryName(String value) {
        return ScmUrlUtils.sanitizePathSegment(value);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Queries Gradle project properties and extracts the configured sourceCompatibility value.
     *
     * @param projectDir Gradle project directory
     * @param jdkPath JAVA_HOME value for command execution
     * @return resolved sourceCompatibility value or {@code null} when missing
     * @throws IOException when process I/O fails
     * @throws InterruptedException when process waiting is interrupted
     */
    public static String retrieveSourceCompatibility(File projectDir, String jdkPath) throws IOException, InterruptedException {
      // Define the Gradle command
      String wrapper = isWindows() ? "gradlew.bat" : "./gradlew";
      String[] command = {wrapper, "properties", "-Porg.gradle.java.installations.auto-download=false"};

      // Start the process
      ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectDir);
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("JAVA_HOME", jdkPath);
      Process process = processBuilder.start();

      // Read the output from the process
      StringBuilder outputBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputBuilder.append(line).append("\n");
            }
        }

      // Wait for the process to complete
      int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new DependencyAnalyzerException("Command execution failed with exit code: " + exitCode);
        }

      // Extract sourceCompatibility from the output
      String output = outputBuilder.toString();
        return extractGradleSourceCompatibility(output);
    }

    private static String extractGradleSourceCompatibility(String output) {
      // Define regular expression pattern to match sourceCompatibility
      Pattern pattern = Pattern.compile("sourceCompatibility:\\s*(\\S+)");
      Matcher matcher = pattern.matcher(output);

        // Find the first occurrence of sourceCompatibility
        if (matcher.find()) {
            return matcher.group(1); // Return the matched sourceCompatibility value
        } else {
            return null; // Return null if sourceCompatibility is not found
        }
    }

    /**
     * Collapses dependency SCM/version pairs to the latest version per SCM URL.
     *
     * @param urlVersions dependency nodes containing SCM URL and version information
     * @return map of SCM URL to highest detected version
     */
    public static Map<String, String> collectLatestVersions(Set<DependencyNode> urlVersions) {
        return urlVersions.stream()
                .collect(Collectors.toMap(
                        DependencyNode::getScmUrl, // Key is the URL
                        DependencyNode::getVersion, // Value is the version
                        (existingVersion, newVersion) -> compareVersions(existingVersion, newVersion) > 0 ? existingVersion
                                : newVersion
                ));
    }

    static int compareVersions(String v1, String v2) {
      String left = v1 == null ? "" : v1.trim();
      String right = v2 == null ? "" : v2.trim();
        if (left.isBlank() && right.isBlank()) {
            return 0;
        }
        if (left.isBlank()) {
            return -1;
        }
        if (right.isBlank()) {
            return 1;
        }
      String[] leftParts = left.split("[._-]");
      String[] rightParts = right.split("[._-]");
      int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
          String leftPart = i < leftParts.length ? leftParts[i] : "0";
          String rightPart = i < rightParts.length ? rightParts[i] : "0";
          int cmp = compareVersionPart(leftPart, rightPart);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int compareVersionPart(String left, String right) {
      boolean leftNumeric = left.matches("\\d+");
      boolean rightNumeric = right.matches("\\d+");
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric) {
            return 1;
        }
        if (rightNumeric) {
            return -1;
        }

      int leftRank = qualifierRank(left);
      int rightRank = qualifierRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        return left.compareToIgnoreCase(right);
    }

    private static int qualifierRank(String qualifier) {
      String q = qualifier == null ? "" : qualifier.trim().toLowerCase(Locale.ROOT);
        if (q.matches("alpha\\d*") || q.matches("a\\d*")) {
            return 1;
        }
        if (q.matches("beta\\d*") || q.matches("b\\d*")) {
            return 2;
        }
        if (q.matches("milestone\\d*") || q.matches("m\\d*")) {
            return 3;
        }
        if (q.matches("rc\\d*") || q.matches("cr\\d*")) {
            return 4;
        }
        if (q.matches("sp\\d*")) {
            return 6;
        }
        return switch (q) {
            case "snapshot" -> 0;
            case "alpha", "a" -> 1;
            case "beta", "b" -> 2;
            case "milestone", "m" -> 3;
            case "rc", "cr" -> 4;
            case "final", "ga", "release" -> 5;
            case "sp" -> 6;
            default -> 7;
        };
    }


    private static boolean isInvalidJdkHome(File jdkHome) {
      boolean binCheck = new File(jdkHome, "bin").isDirectory();
      boolean libCheck = new File(jdkHome, "lib").isDirectory();
        log.info("The bin folder for path {} exists? {}", jdkHome.getPath(), binCheck);
        log.info("The lib folder for path {} exists? {}", jdkHome.getPath(), libCheck);
        return !binCheck || !libCheck;
    }

    /**
     * Formats repository names as a bullet list prefixed with a section header.
     *
     * @param header section title
     * @param names repository names
     * @return formatted multi-line text
     */
    public static String concatenateRepoNames(String header, Set<String> names) {
      StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n");
        sb.append(names.stream()
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n")));
        return sb.toString();
    }
}

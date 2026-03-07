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
    public static final String SETTINGS_FILE_PATH = "env-settings.properties";

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


    public static String getProjectName(File pomFile) throws Exception {

        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(pomFile);
        doc.getDocumentElement().normalize();

        NodeList nodeList = doc.getElementsByTagName("name");
        return nodeList.item(0).getTextContent();
    }

    public static String getPropertyFromSetting(String property) {
        var properties = loadSettings();
        return properties.getProperty(property, "");
    }

    public static Properties loadSettings() {
        Properties properties = new Properties();
        try (InputStream is = new FileInputStream(SETTINGS_FILE_PATH)) {
            properties.load(is);
        } catch (IOException e) {
            // Handle file not found or other errors
            log.error(e.getMessage());
            throw new DependencyAnalyzerException(e);
        }
        return properties;
    }

    public static void saveSettings(ObservableList<EnvSetting> settingsList) {
        Properties properties = new Properties();
        settingsList.forEach(setting -> properties.setProperty(setting.getName(), setting.getValue()));

        try (OutputStream os = new FileOutputStream(SETTINGS_FILE_PATH)) {
            properties.store(os, "Environment Settings");
            log.info("Settings saved to {}", SETTINGS_FILE_PATH);
        } catch (IOException e) {
            log.error(e.getMessage());
            throw new DependencyAnalyzerException(e);
        }
    }

    public static boolean arePropertiesConfiguredAndValid() {
        var properties = Utils.loadSettings();
        var java8Home = properties.getProperty("JAVA8_HOME", "");
        var java11Home = properties.getProperty("JAVA11_HOME", "");
        var java17Home = properties.getProperty("JAVA17_HOME", "");
        if (java8Home.isEmpty() || java11Home.isEmpty() || java17Home.isEmpty()) {
            showError("""
                    Dependencies might be targeted for compilation with a different JDK version,
                    please configure JAVA8_HOME, JAVA11_HOME & JAVA17_HOME in settings -> environment settings.
                    """);
            return false;
        }

        if (isValidJdkHome(new File(java8Home))) {
            showError("""
                    JAVA8_HOME environment variable is not a valid JAVA_HOME.
                    JAVA8_HOME should point to the root directory path of the JDK.
                    """);
            return false;
        } else if (isValidJdkHome(new File(java11Home))) {
            showError("""
                    JAVA11_HOME environment variable is not a valid JAVA_HOME.
                    JAVA11_HOME should point to the root directory path of the JDK.
                    """);
            return false;
        } else if (isValidJdkHome(new File(java17Home))) {
            showError("""
                    JAVA17_HOME environment variable is not a valid JAVA_HOME.
                    JAVA17_HOME should point to the root directory path of the JDK.
                    """);
            return false;
        }
        return true;
    }

    public static void createSettingsFile() {

        // Check if the file exists
        File file = new File(SETTINGS_FILE_PATH);
        if (!file.exists()) {
            try {
                Properties properties = new Properties();
                file.createNewFile();

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    properties.store(fos, "Environment Settings");
                    log.info("Properties file created successfully.");
                } catch (IOException e) {
                    log.error(e.getMessage());
                    throw new DependencyAnalyzerException(e);
                }
            } catch (IOException e) {
                log.error(e.getMessage());
                throw new DependencyAnalyzerException(e);
            }
        } else {
            log.info("Properties file already exists.");
        }
    }

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

    public static String getRepositoriesPath() {
        return getRepositoriesPath(null);
    }

    public static String getThirdPartyRepositoriesPath(String projectName) {
        return ensureDirectory(Path.of(getRepositoriesPath(projectName), THIRD_PARTY_REPOS)).toString();
    }

    public static String getFourthPartyRepositoriesPath(String projectName) {
        return ensureDirectory(Path.of(getRepositoriesPath(projectName), FOURTH_PARTY_REPOS)).toString();
    }

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


    private static boolean isValidJdkHome(File jdkHome) {
        var binCheck = new File(jdkHome, "bin").isDirectory();
        var libCheck = new File(jdkHome, "lib").isDirectory();
        log.info("The bin folder for path {} exists? {}", jdkHome.getPath(), binCheck);
        log.info("The lib folder for path {} exists? {}", jdkHome.getPath(), libCheck);
        return !binCheck || !libCheck;
    }

    public static String concatenateRepoNames(String header, Set<String> names) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n");
        sb.append(names.stream()
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n")));
        return sb.toString();
    }
}

package io.botsteve.dependencyanalyzer.tasks;

import static io.botsteve.dependencyanalyzer.utils.ProxyUtil.configureProxyIfEnvAvailable;

import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import io.botsteve.dependencyanalyzer.utils.ForceDeleteUtil;
import io.botsteve.dependencyanalyzer.utils.LogUtils;
import io.botsteve.dependencyanalyzer.utils.Utils;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads and configures required JDK runtimes for build orchestration.
 *
 * <p>The task fetches Adoptium archives, extracts them into {@code downloaded_jdks}, updates
 * {@code config/env-settings.properties}, and emits weighted progress updates for UI display.</p>
 */
public class JdkDownloadTask extends Task<Map<String, String>> {

  private static final Logger log = LoggerFactory.getLogger(JdkDownloadTask.class);
  private static final String ADOPTIUM_TEMPLATE =
      "https://api.adoptium.net/v3/binary/latest/%d/ga/%s/%s/jdk/hotspot/normal/eclipse";
  private static final String JAVA8_X64_REASON =
      "JDK 8 aarch64 binaries are not available in this distribution API, so x64 is used.";
  private static final long PROGRESS_SCALE = 10_000L;
  private static final int DOWNLOAD_PHASE_WEIGHT = 65;
  private static final int EXTRACT_PHASE_WEIGHT = 30;

  private final Set<String> requestedSettings;

  /**
   * @param requestedSettings requested JDK setting keys (for example JAVA8_HOME); empty means all required keys
   */
  public JdkDownloadTask(Set<String> requestedSettings) {
    this.requestedSettings = requestedSettings == null ? Set.of() : Set.copyOf(requestedSettings);
  }

  @Override
  protected void scheduled() {
    super.scheduled();
    updateProgress(0, PROGRESS_SCALE);
    updateMessage("Preparing JDK downloads...");
  }

  /**
   * Runs the full download/extract/configure pipeline.
   *
   * @return resolved JAVA*_HOME mapping persisted to settings
   */
  @Override
  protected Map<String, String> call() {
    try {
      configureProxyIfEnvAvailable();
    } catch (URISyntaxException e) {
      throw new DependencyAnalyzerException("Failed to parse proxy configuration from environment", e);
    }

    String os = normalizeOs(System.getProperty("os.name", ""));
    String hostArch = normalizeArchitecture(System.getProperty("os.arch", ""));

    Set<String> effectiveSettings = resolveEffectiveSettings(requestedSettings);
    if (effectiveSettings.isEmpty()) {
      return Map.of();
    }

    log.info("Starting JDK bootstrap. Requested settings: {}", effectiveSettings);

    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    Path baseDir = Path.of(System.getProperty(LogUtils.BASE_DIR_PROPERTY, System.getProperty("user.dir")));
    Path jdkBaseDir = baseDir.resolve(Utils.DOWNLOADED_JDKS);
    Path archivesDir = jdkBaseDir.resolve("archives");
    ensureDirectory(archivesDir);
    ensureDirectory(jdkBaseDir);

    Map<String, String> resolvedHomes = new LinkedHashMap<>();
    int totalJdks = effectiveSettings.size();
    int step = 0;
    updateOverallProgress(0, totalJdks, 0.0, "Preparing JDK downloads...");

    for (String settingKey : effectiveSettings) {
      final int currentIndex = step;
      int version = toJdkVersion(settingKey);
      String downloadArch = resolveDownloadArchitecture(version, hostArch);
      String startMessage = buildDownloadMessage(version, os, downloadArch, hostArch);
      updateOverallProgress(currentIndex, totalJdks, 0.0, startMessage);

      log.info("JDK {} [{}] - starting download for os={} arch={}", version, settingKey, os, downloadArch);
      if (version == 8 && !"x64".equals(hostArch)) {
        log.info("JDK 8 architecture override applied: hostArch={} -> downloadArch=x64. Reason: {}",
            hostArch,
            JAVA8_X64_REASON);
      }

      try {
        DownloadedArchive archive = downloadArchive(client,
            version,
            os,
            downloadArch,
            archivesDir,
            (fraction, message) -> updateOverallProgress(currentIndex,
                totalJdks,
                clamp(0.0 + (fraction * (DOWNLOAD_PHASE_WEIGHT / 100.0))),
                message));

        Path installTarget = resolveInstallDirectory(jdkBaseDir, settingKey);
        replaceDirectory(installTarget);

        log.info("JDK {} [{}] - extracting {} into {}", version, settingKey, archive.archivePath(), installTarget);
        updateOverallProgress(currentIndex,
            totalJdks,
            DOWNLOAD_PHASE_WEIGHT / 100.0,
            "Extracting JDK " + version + "...");
        extractArchive(archive.archivePath(),
            archive.extension(),
            installTarget,
            version,
            (fraction, message) -> updateOverallProgress(currentIndex,
                totalJdks,
                clamp((DOWNLOAD_PHASE_WEIGHT / 100.0)
                    + (fraction * (EXTRACT_PHASE_WEIGHT / 100.0))),
                message));

        Path javaHome = resolveJavaHome(installTarget);
        resolvedHomes.put(settingKey, javaHome.toAbsolutePath().toString());
        updateOverallProgress(currentIndex, totalJdks, 1.0, "Configured " + settingKey + " = " + javaHome);
        log.info("JDK {} [{}] ready at {}", version, settingKey, javaHome);
      } catch (RuntimeException e) {
        log.error("Failed to download/configure JDK {} using os={} arch={} setting={}",
            version,
            os,
            downloadArch,
            settingKey,
            e);
        throw e;
      }

      step++;
    }

    Properties settings = Utils.loadSettings();
    resolvedHomes.forEach(settings::setProperty);
    Utils.saveSettings(settings);
    log.info("JDK download finished. Updated settings for keys: {}", resolvedHomes.keySet());
    updateProgress(PROGRESS_SCALE, PROGRESS_SCALE);
    updateMessage("JDK download complete. Environment settings were updated.");
    return resolvedHomes;
  }

  static Set<String> resolveEffectiveSettings(Set<String> requested) {
    List<String> ordered = Utils.REQUIRED_JDK_SETTINGS;
    Set<String> out = new LinkedHashSet<>();
    if (requested == null || requested.isEmpty()) {
      out.addAll(ordered);
      return out;
    }
    for (String key : ordered) {
      if (requested.contains(key)) {
        out.add(key);
      }
    }
    return out;
  }

  static String normalizeOs(String osName) {
    String value = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (value.contains("win")) {
      return "windows";
    }
    if (value.contains("mac") || value.contains("darwin")) {
      return "mac";
    }
    if (value.contains("nux") || value.contains("nix") || value.contains("aix")) {
      return "linux";
    }
    throw new DependencyAnalyzerException("Unsupported operating system: " + osName);
  }

  static String normalizeArchitecture(String architecture) {
    String value = architecture == null ? "" : architecture.toLowerCase(Locale.ROOT);
    if (value.contains("aarch64") || value.contains("arm64")) {
      return "aarch64";
    }
    if (value.contains("amd64") || value.contains("x86_64") || value.equals("x64")) {
      return "x64";
    }
    throw new DependencyAnalyzerException("Unsupported CPU architecture: " + architecture);
  }

  static int toJdkVersion(String settingKey) {
    return switch (settingKey) {
      case "JAVA8_HOME" -> 8;
      case "JAVA11_HOME" -> 11;
      case "JAVA17_HOME" -> 17;
      case "JAVA21_HOME" -> 21;
      default -> throw new DependencyAnalyzerException("Unsupported JDK setting key: " + settingKey);
    };
  }

  static URI buildAdoptiumUri(int version, String os, String arch) {
    return URI.create(ADOPTIUM_TEMPLATE.formatted(version, os, arch));
  }

  static double computeOverallProgressFraction(int jdkIndex, int totalJdks, double withinJdkFraction) {
    if (totalJdks <= 0) {
      return 0.0;
    }
    double clampedWithin = clamp(withinJdkFraction);
    double value = (jdkIndex + clampedWithin) / (double) totalJdks;
    return clamp(value);
  }

  static String resolveDownloadArchitecture(int version, String hostArch) {
    if (version == 8) {
      return "x64";
    }
    return hostArch;
  }

  static Path resolveInstallDirectory(Path jdkBaseDir, String settingKey) {
    return jdkBaseDir.resolve(settingKey);
  }

  static String buildDownloadMessage(int version, String os, String downloadArch, String hostArch) {
    if (version == 8 && !"x64".equals(hostArch)) {
      return "Downloading JDK 8 for " + os + " (x64). " + JAVA8_X64_REASON;
    }
    return "Downloading JDK " + version + " for " + os + " (" + downloadArch + ")...";
  }

  /**
   * @return user-facing explanation for the JDK8 x64-on-ARM fallback policy
   */
  public static String java8X64Explanation() {
    return JAVA8_X64_REASON;
  }

  private void updateOverallProgress(int jdkIndex, int totalJdks, double withinJdkFraction, String message) {
    double overall = computeOverallProgressFraction(jdkIndex, totalJdks, withinJdkFraction);
    long completed = Math.round(overall * PROGRESS_SCALE);
    updateProgress(completed, PROGRESS_SCALE);
    if (message != null && !message.isBlank()) {
      updateMessage(message);
    }
  }

  private DownloadedArchive downloadArchive(HttpClient client,
                                            int version,
                                            String os,
                                            String arch,
                                            Path archivesDir,
                                            StageProgress stageProgress) {
    URI uri = buildAdoptiumUri(version, os, arch);
    HttpRequest request = HttpRequest.newBuilder()
        .uri(uri)
        .GET()
        .timeout(Duration.ofMinutes(5))
        .build();

    try {
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() >= 400) {
        throw new DependencyAnalyzerException("JDK download failed with HTTP status " + response.statusCode() +
            " for " + uri);
      }

      String extension = resolveArchiveExtension(os,
          response.headers().firstValue("content-disposition").orElse(""),
          response.uri());

      Path archivePath = archivesDir.resolve("jdk-" + version + "-" + os + "-" + arch + extension);
      long contentLength = parsePositiveLong(response.headers().firstValue("content-length").orElse(""));

      try (InputStream body = response.body()) {
        writeArchiveWithProgress(body, archivePath, version, contentLength, stageProgress);
      }

      stageProgress.report(1.0, "Downloaded JDK " + version + " archive to " + archivePath.getFileName());
      log.info("JDK {} download completed: {}", version, archivePath);
      return new DownloadedArchive(archivePath, extension);
    } catch (IOException e) {
      throw new DependencyAnalyzerException("I/O error while downloading JDK " + version, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DependencyAnalyzerException("JDK download was interrupted for version " + version, e);
    }
  }

  static String resolveArchiveExtension(String os, String contentDisposition, URI responseUri) {
    String disposition = contentDisposition == null ? "" : contentDisposition.toLowerCase(Locale.ROOT);
    String path = responseUri == null ? "" : Objects.toString(responseUri.getPath(), "").toLowerCase(Locale.ROOT);

    if (disposition.contains(".zip") || path.endsWith(".zip")) {
      return ".zip";
    }
    if (disposition.contains(".tar.gz") || path.endsWith(".tar.gz") || path.endsWith(".tgz")) {
      return ".tar.gz";
    }
    return "windows".equals(os) ? ".zip" : ".tar.gz";
  }

  private static long parsePositiveLong(String value) {
    if (value == null || value.isBlank()) {
      return -1;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      return parsed > 0 ? parsed : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private void writeArchiveWithProgress(InputStream body,
                                        Path archivePath,
                                        int version,
                                        long contentLength,
                                        StageProgress stageProgress) throws IOException {
    ensureDirectory(archivePath.getParent());

    final byte[] buffer = new byte[64 * 1024];
    long downloaded = 0L;
    int lastReportedPercent = -1;
    int lastLoggedBucket = -1;
    long nextUnknownLogThresholdBytes = 5L * 1024L * 1024L;

    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(archivePath))) {
      int read;
      while ((read = body.read(buffer)) != -1) {
        out.write(buffer, 0, read);
        downloaded += read;

        if (contentLength > 0) {
          double fraction = clamp((double) downloaded / contentLength);
          int percent = (int) Math.floor(fraction * 100);
          if (percent > lastReportedPercent) {
            stageProgress.report(fraction, "Downloading JDK " + version + "... " + percent + "%");
            lastReportedPercent = percent;

            int logBucket = percent / 10;
            if (logBucket > lastLoggedBucket) {
              log.info("JDK {} download progress: {}%", version, logBucket * 10);
              lastLoggedBucket = logBucket;
            }
          }
        } else if (downloaded >= nextUnknownLogThresholdBytes) {
          long downloadedMb = downloaded / (1024L * 1024L);
          stageProgress.report(0.0, "Downloading JDK " + version + "... " + downloadedMb + " MB");
          log.info("JDK {} download progress: {} MB (content-length unavailable)", version, downloadedMb);
          nextUnknownLogThresholdBytes += 5L * 1024L * 1024L;
        }
      }
    }
  }

  private void extractArchive(Path archivePath,
                              String extension,
                              Path targetDirectory,
                              int version,
                              StageProgress stageProgress) {
    try {
      ensureDirectory(targetDirectory);
      if (".zip".equals(extension)) {
        extractZip(archivePath, targetDirectory, version, stageProgress);
        return;
      }
      extractTarGz(archivePath, targetDirectory, version, stageProgress);
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to extract archive " + archivePath, e);
    }
  }

  private void extractZip(Path zipPath,
                          Path targetDirectory,
                          int version,
                          StageProgress stageProgress) throws IOException {
    long totalBytes = Files.size(zipPath);
    int[] lastReportedPercent = {-1};
    int[] lastLoggedBucket = {-1};

    try (CountingInputStream countingInputStream = new CountingInputStream(Files.newInputStream(zipPath));
         ZipInputStream zis = new ZipInputStream(countingInputStream)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        Path outPath = targetDirectory.resolve(entry.getName()).normalize();
        if (!outPath.startsWith(targetDirectory)) {
          throw new DependencyAnalyzerException("Blocked zip-slip entry: " + entry.getName());
        }
        if (entry.isDirectory()) {
          ensureDirectory(outPath);
        } else {
          ensureDirectory(outPath.getParent());
          try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outPath))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = zis.read(buffer)) != -1) {
              out.write(buffer, 0, read);
              reportZipExtractionProgress(version,
                  totalBytes,
                  countingInputStream.getByteCount(),
                  stageProgress,
                  lastReportedPercent,
                  lastLoggedBucket);
            }
          }
        }
        zis.closeEntry();
      }
    }
    stageProgress.report(1.0, "Extracting JDK " + version + "... 100%");
    log.info("JDK {} extraction progress: 100%", version);
  }

  private void reportZipExtractionProgress(int version,
                                           long totalBytes,
                                           long consumedBytes,
                                           StageProgress stageProgress,
                                           int[] lastReportedPercent,
                                           int[] lastLoggedBucket) {
    if (totalBytes <= 0) {
      return;
    }
    int percent = (int) Math.min(100, (consumedBytes * 100L) / totalBytes);
    if (percent > lastReportedPercent[0]) {
      stageProgress.report(clamp(percent / 100.0), "Extracting JDK " + version + "... " + percent + "%");
      lastReportedPercent[0] = percent;

      int logBucket = percent / 10;
      if (logBucket > lastLoggedBucket[0]) {
        log.info("JDK {} extraction progress: {}%", version, logBucket * 10);
        lastLoggedBucket[0] = logBucket;
      }
    }
  }

  private long countTarEntries(Path tarGzPath) throws IOException {
    ProcessBuilder pb = new ProcessBuilder("tar", "-tzf", tarGzPath.toString());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    long count = 0;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          count++;
        }
      }
    }

    try {
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        log.warn("Failed to pre-count tar entries for {}. Progress will be coarse.", tarGzPath);
        return -1;
      }
      return count;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DependencyAnalyzerException("Interrupted while counting tar entries for " + tarGzPath, e);
    }
  }

  private void extractTarGz(Path tarGzPath,
                            Path targetDirectory,
                            int version,
                            StageProgress stageProgress) throws IOException {
    long totalEntries = countTarEntries(tarGzPath);
    ProcessBuilder pb = new ProcessBuilder("tar", "-xvzf", tarGzPath.toString(), "-C", targetDirectory.toString());
    pb.redirectErrorStream(true);
    Process process = pb.start();

    List<String> output = new ArrayList<>();
    AtomicInteger extractedEntries = new AtomicInteger(0);
    int lastReportedPercent = -1;
    int lastLoggedBucket = -1;

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.add(line);
        if (line.isBlank()) {
          continue;
        }
        int processed = extractedEntries.incrementAndGet();

        int percent;
        if (totalEntries > 0) {
          percent = (int) Math.min(100, (processed * 100L) / totalEntries);
        } else {
          percent = Math.min(99, processed % 100);
        }

        if (percent > lastReportedPercent) {
          stageProgress.report(clamp(percent / 100.0), "Extracting JDK " + version + "... " + percent + "%");
          lastReportedPercent = percent;

          int logBucket = percent / 10;
          if (logBucket > lastLoggedBucket) {
            log.info("JDK {} extraction progress: {}%", version, logBucket * 10);
            lastLoggedBucket = logBucket;
          }
        }
      }
    }

    int exitCode;
    try {
      exitCode = process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DependencyAnalyzerException("Interrupted while extracting " + tarGzPath, e);
    }

    if (exitCode != 0) {
      throw new DependencyAnalyzerException("tar extraction failed for " + tarGzPath + ": " + String.join("\n", output));
    }

    stageProgress.report(1.0, "Extracting JDK " + version + "... 100%");
    log.info("JDK {} extraction progress: 100%", version);
  }

  static Path resolveJavaHome(Path installRoot) {
    String executable = isWindowsHost() ? "java.exe" : "java";
    try (var stream = Files.walk(installRoot, 10)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> executable.equals(path.getFileName().toString()))
          .filter(path -> path.getParent() != null && "bin".equals(path.getParent().getFileName().toString()))
          .map(path -> path.getParent().getParent())
          .filter(Objects::nonNull)
          .filter(path -> Files.isDirectory(path.resolve("lib")))
          .min((left, right) -> Integer.compare(left.getNameCount(), right.getNameCount()))
          .orElseThrow(() -> new DependencyAnalyzerException("Unable to resolve JAVA_HOME from " + installRoot));
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to detect JAVA_HOME in extracted directory " + installRoot, e);
    }
  }

  private static void replaceDirectory(Path path) {
    try {
      if (Files.exists(path)) {
        ForceDeleteUtil.forceDeleteDirectory(path);
      }
      ensureDirectory(path);
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to prepare installation directory " + path, e);
    }
  }

  private static void ensureDirectory(Path path) {
    if (path == null) {
      return;
    }
    try {
      if (!Files.exists(path)) {
        Files.createDirectories(path);
      }
    } catch (IOException e) {
      throw new DependencyAnalyzerException("Failed to create directory: " + path, e);
    }
  }

  private static boolean isWindowsHost() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static double clamp(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }

  @FunctionalInterface
  private interface StageProgress {
    void report(double fraction, String message);
  }

  private static final class CountingInputStream extends FilterInputStream {

    private long byteCount;

    private CountingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) {
        byteCount++;
      }
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int read = super.read(b, off, len);
      if (read > 0) {
        byteCount += read;
      }
      return read;
    }

    private long getByteCount() {
      return byteCount;
    }
  }

  private record DownloadedArchive(Path archivePath, String extension) {
  }
}

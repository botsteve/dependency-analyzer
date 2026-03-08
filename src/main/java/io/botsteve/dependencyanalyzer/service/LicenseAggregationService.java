package io.botsteve.dependencyanalyzer.service;

import static io.botsteve.dependencyanalyzer.utils.Utils.getRepositoriesPath;
import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.fixNonResolvableScmRepositorise;

import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.model.LicenseInfo;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class LicenseAggregationService {

  private static final Logger log = LoggerFactory.getLogger(LicenseAggregationService.class);

  private static final String REPORT_TITLE = "# Public Licenses & Copyright Notice";
  private static final String REPORT_FILE_PREFIX = "PUBLIC-LICENSES-NOTICE";
  private static final String REPORT_DIRECTORY_NAME = "licenses";
  private static final int MAX_SCAN_DEPTH = 4;
  private static final long MAX_LEGAL_FILE_SIZE_BYTES = 1_500_000;
  private static final Pattern COPYRIGHT_LINE = Pattern.compile("(?i)^\\s*copyright(?:\\s*\\(c\\)|\\s*©)?\\b.*$");

  private final Path repositoriesRoot;

  /**
   * Creates a license aggregation service scoped to one analyzed project.
   *
   * @param projectName project name used to resolve downloaded repository directories
   */
  public LicenseAggregationService(String projectName) {
    this.repositoriesRoot = Path.of(getRepositoriesPath(projectName));
  }

  public LicenseAggregationService() {
    this(null);
  }

  /**
   * Builds an aggregated public license report for selected dependencies and their transitive children.
   *
   * @param selectedDependencies selected third-party dependencies
   * @return markdown report containing license, notice, and copyright sections
   */
  public String generatePublicLicenseReport(Collection<DependencyNode> selectedDependencies) {
    if (selectedDependencies == null || selectedDependencies.isEmpty()) {
      return REPORT_TITLE + "\n\nNo dependencies selected.";
    }

    Map<DependencyResolutionKey, ResolvedDependency> resolvedCache = new HashMap<>();
    StringBuilder sb = new StringBuilder();
    sb.append(REPORT_TITLE).append("\n\n");

    int index = 0;
    for (DependencyNode selected : selectedDependencies) {
      if (selected == null) {
        continue;
      }

      if (index++ > 0) {
        sb.append("\n---\n\n");
      }

      ResolvedDependency main = resolveDependency(selected, resolvedCache, null);
      List<ResolvedDependency> fourthParty = resolveFourthParty(selected, resolvedCache);
      appendReportForResolvedDependency(sb, main, fourthParty);
    }

    return sb.toString();
  }

  public LicenseReportGenerationResult generateAndStorePublicLicenseReports(Collection<DependencyNode> selectedDependencies)
      throws Exception {
    if (selectedDependencies == null || selectedDependencies.isEmpty()) {
      return new LicenseReportGenerationResult(List.of(), List.of(), List.of());
    }

    Map<DependencyResolutionKey, ResolvedDependency> resolvedCache = new HashMap<>();
    List<Path> generatedFiles = new ArrayList<>();
    Set<String> includedDependencies = new LinkedHashSet<>();
    Map<String, String> skippedDependencies = new LinkedHashMap<>();

    for (DependencyNode selected : selectedDependencies) {
      if (selected == null) {
        continue;
      }

      ResolvedDependency main = resolveDependency(selected, resolvedCache, null);
      Optional<String> mainSkipReason = resolveSkipReason(main);
      if (mainSkipReason.isPresent()) {
        skippedDependencies.putIfAbsent(main.coordinate().toDisplayString(), mainSkipReason.get());
        continue;
      }

      List<ResolvedDependency> fourthParty = resolveFourthParty(selected, resolvedCache);
      List<ResolvedDependency> eligibleFourthParty = new ArrayList<>();
      for (ResolvedDependency dependency : fourthParty) {
        Optional<String> skipReason = resolveSkipReason(dependency);
        if (skipReason.isPresent()) {
          skippedDependencies.putIfAbsent(dependency.coordinate().toDisplayString(), skipReason.get());
          continue;
        }
        eligibleFourthParty.add(dependency);
        includedDependencies.add(dependency.coordinate().toDisplayString());
      }

      includedDependencies.add(main.coordinate().toDisplayString());

      StringBuilder report = new StringBuilder();
      report.append(REPORT_TITLE).append("\n\n");
      appendReportForResolvedDependency(report, main, eligibleFourthParty);

      Path outputFile = resolveOutputFilePath(selected, main.coordinate());
      Files.createDirectories(outputFile.getParent());
      Files.writeString(outputFile, report.toString(), StandardCharsets.UTF_8);
      generatedFiles.add(outputFile);
    }

    return new LicenseReportGenerationResult(
        generatedFiles,
        new ArrayList<>(includedDependencies),
        skippedDependencies.entrySet().stream()
            .map(entry -> entry.getKey() + " - " + entry.getValue())
            .toList());
  }

  private void appendReportForResolvedDependency(StringBuilder sb,
                                                 ResolvedDependency main,
                                                 List<ResolvedDependency> fourthParty) {
    appendMainComponentSection(sb, main);
    appendFourthPartySection(sb, main, fourthParty);
  }

  private Path resolveOutputFilePath(DependencyNode selected, DependencyCoordinate coordinate) {
    return repositoriesRoot
        .resolve(REPORT_DIRECTORY_NAME)
        .resolve(buildAggregatedLicenseFileName(coordinate));
  }

  private static String buildAggregatedLicenseFileName(DependencyCoordinate coordinate) {
    String group = ScmUrlUtils.sanitizePathSegment(safe(coordinate.groupId()));
    String artifact = ScmUrlUtils.sanitizePathSegment(safe(coordinate.artifactId()));
    String version = ScmUrlUtils.sanitizePathSegment(safe(coordinate.version()));
    if (group.isBlank()) {
      group = "unknown-group";
    }
    if (artifact.isBlank()) {
      artifact = "unknown-artifact";
    }
    if (version.isBlank()) {
      return REPORT_FILE_PREFIX + "-" + group + "-" + artifact + ".md";
    }
    return REPORT_FILE_PREFIX + "-" + group + "-" + artifact + "-" + version + ".md";
  }

  private static Optional<String> resolveSkipReason(ResolvedDependency dependency) {
    if (dependency == null) {
      return Optional.of("Dependency resolution failed");
    }
    if (dependency.coordinate().groupId().isBlank()
        || dependency.coordinate().artifactId().isBlank()
        || dependency.coordinate().version().isBlank()) {
      return Optional.of("Invalid dependency coordinate");
    }
    String note = safe(dependency.resolutionNote());
    if (note.toLowerCase(Locale.ROOT).contains("repository is not downloaded locally")) {
      return Optional.of(note);
    }
    return Optional.empty();
  }

  private List<ResolvedDependency> resolveFourthParty(DependencyNode selected,
                                                      Map<DependencyResolutionKey, ResolvedDependency> resolvedCache) {
    Set<DependencyNode> allChildren = new LinkedHashSet<>();
    collectChildrenRecursively(selected, allChildren);
    DependencyCoordinate selectedCoordinate = new DependencyCoordinate(
        safe(selected == null ? null : selected.getGroupId()),
        safe(selected == null ? null : selected.getArtifactId()),
        safe(selected == null ? null : selected.getVersion()));
    String parentRepoName = resolveRepoName(selected, selectedCoordinate);

    List<ResolvedDependency> out = new ArrayList<>();
    for (DependencyNode child : allChildren) {
      out.add(resolveDependency(child, resolvedCache, parentRepoName));
    }
    out.sort(Comparator.comparing(r -> r.coordinate().toDisplayString()));
    return out;
  }

  private static void collectChildrenRecursively(DependencyNode node, Set<DependencyNode> out) {
    if (node == null || node.getChildren() == null) {
      return;
    }
    for (DependencyNode child : node.getChildren()) {
      if (child == null) {
        continue;
      }
      out.add(child);
      collectChildrenRecursively(child, out);
    }
  }

  private ResolvedDependency resolveDependency(DependencyNode node,
                                               Map<DependencyResolutionKey, ResolvedDependency> resolvedCache,
                                               String preferredFourthPartyParentRepoName) {
    DependencyCoordinate coordinate = new DependencyCoordinate(
        safe(node.getGroupId()),
        safe(node.getArtifactId()),
        safe(node.getVersion()));

    if (coordinate.groupId().isBlank() || coordinate.artifactId().isBlank() || coordinate.version().isBlank()) {
      return ResolvedDependency.empty(node);
    }

    DependencyResolutionKey cacheKey = new DependencyResolutionKey(
        coordinate,
        safe(preferredFourthPartyParentRepoName));

    ResolvedDependency cached = resolvedCache.get(cacheKey);
    if (cached != null) {
      return cached.withPreferredSourceUrl(firstNonBlank(node.getScmUrl(), cached.sourceUrl()));
    }

    Path localRepo = resolveLocalRepoPath(node, coordinate, preferredFourthPartyParentRepoName);
    PomMetadata pomMetadata = readLocalPomMetadata(localRepo);
    List<SourceFileContent> sourceFiles = readLegalFilesFromLocalRepo(localRepo);

    String sourceUrl = fixNonResolvableScmRepositorise(
        firstNonBlank(node.getScmUrl(), pomMetadata.scmUrl(), pomMetadata.projectUrl()),
        coordinate.groupId(),
        coordinate.artifactId());
    String copyright = firstNonBlank(
        normalizeCopyrightHolder(pomMetadata.organizationName()),
        extractCopyrightHolderFromLegalFiles(sourceFiles),
        "Unknown");

    LicenseBundle bundle = resolveLicenseBundle(pomMetadata.licenses(), sourceFiles, localRepo);
    List<SourceFileContent> noticeFiles = filterNoticeFiles(sourceFiles);

    ResolvedDependency resolved = new ResolvedDependency(
        coordinate,
        sourceUrl,
        copyright,
        bundle.licenseName(),
        bundle.licenseText(),
        noticeFiles,
        bundle.licenseUrl(),
        bundle.resolutionNote());

    resolvedCache.put(cacheKey, resolved);
    return resolved;
  }

  private Path resolveLocalRepoPath(DependencyNode node,
                                    DependencyCoordinate coordinate,
                                    String preferredFourthPartyParentRepoName) {
    String repoName = resolveRepoName(node, coordinate);

    if (preferredFourthPartyParentRepoName != null && !preferredFourthPartyParentRepoName.isBlank()) {
      Path preferredFourthParty = repositoriesRoot
          .resolve("4th-party")
          .resolve(preferredFourthPartyParentRepoName)
          .resolve(repoName);
      if (Files.isDirectory(preferredFourthParty)) {
        return preferredFourthParty;
      }
    }

    Path thirdPartyCandidate = repositoriesRoot.resolve("3rd-party").resolve(repoName);
    if (Files.isDirectory(thirdPartyCandidate)) {
      return thirdPartyCandidate;
    }

    Path legacyFlatCandidate = repositoriesRoot.resolve(repoName);
    if (Files.isDirectory(legacyFlatCandidate)) {
      return legacyFlatCandidate;
    }

    Path nestedFourthParty = findNestedRepo(repositoriesRoot.resolve("4th-party"), repoName);
    if (nestedFourthParty != null) {
      return nestedFourthParty;
    }

    return legacyFlatCandidate;
  }

  private static String resolveRepoName(DependencyNode node, DependencyCoordinate coordinate) {
    String repoName = "";
    if (node != null && node.getScmUrl() != null && !node.getScmUrl().isBlank()) {
      repoName = ScmUrlUtils.resolveRepoName(node.getScmUrl(), coordinate.artifactId());
    }
    if (repoName == null || repoName.isBlank()) {
      repoName = coordinate.artifactId();
    }
    return safe(repoName);
  }

  private Path findNestedRepo(Path root, String repoName) {
    if (root == null || repoName == null || repoName.isBlank() || !Files.isDirectory(root)) {
      return null;
    }

    try (Stream<Path> stream = Files.walk(root, 4)) {
      List<Path> matches = stream
          .filter(Files::isDirectory)
          .filter(path -> Files.isDirectory(path.resolve(".git")))
          .filter(path -> repoName.equals(path.getFileName().toString()))
          .limit(3)
          .toList();

      if (matches.isEmpty()) {
        return null;
      }

      if (matches.size() > 1) {
        log.warn("Ambiguous nested repository resolution for '{}'. Found {} matches under {}. Skipping nested lookup.",
            repoName, matches.size(), root);
        return null;
      }

      return matches.get(0);
    } catch (Exception e) {
      log.debug("Failed to walk nested repositories under {} while resolving repo '{}'", root, repoName, e);
      return null;
    }
  }

  private PomMetadata readLocalPomMetadata(Path localRepo) {
    if (localRepo == null || !Files.isDirectory(localRepo)) {
      return PomMetadata.empty();
    }

    Path pom = localRepo.resolve("pom.xml");
    if (!Files.exists(pom)) {
      return PomMetadata.empty();
    }

    try {
      String content = Files.readString(pom, StandardCharsets.UTF_8);
      Document doc = parseXml(content);
      Element root = doc.getDocumentElement();

      String projectUrl = firstChildText(root, "url").orElse("");
      String scmUrl = firstChildElement(root, "scm")
          .flatMap(scm -> firstChildText(scm, "url"))
          .orElse("");
      String organizationName = firstChildElement(root, "organization")
          .flatMap(org -> firstChildText(org, "name"))
          .orElse("");
      List<LicenseInfo> licenses = parseLicenses(root);

      return new PomMetadata(projectUrl, scmUrl, organizationName, licenses);
    } catch (Exception e) {
      log.debug("Failed reading local pom metadata from {}", localRepo, e);
      return PomMetadata.empty();
    }
  }

  private List<SourceFileContent> readLegalFilesFromLocalRepo(Path localRepo) {
    if (localRepo == null || !Files.isDirectory(localRepo)) {
      return List.of();
    }

    List<SourceFileContent> out = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(localRepo, MAX_SCAN_DEPTH)) {
      stream
          .filter(Files::isRegularFile)
          .sorted()
          .forEach(path -> readLegalFile(localRepo, path).ifPresent(out::add));
    } catch (Exception e) {
      log.debug("Failed reading legal files from {}", localRepo, e);
      return List.of();
    }
    return out;
  }

  private Optional<SourceFileContent> readLegalFile(Path localRepo, Path path) {
    String fileName = path.getFileName().toString();
    if (!isInterestingLegalFile(fileName) || isGeneratedLicenseReport(fileName)) {
      return Optional.empty();
    }

    try {
      if (Files.size(path) > MAX_LEGAL_FILE_SIZE_BYTES) {
        return Optional.empty();
      }
      String text = Files.readString(path, StandardCharsets.UTF_8).trim();
      if (text.isBlank()) {
        return Optional.empty();
      }
      String relative = "./" + localRepo.relativize(path).toString().replace('\\', '/');
      return Optional.of(new SourceFileContent(relative, text));
    } catch (Exception e) {
      log.debug("Failed reading legal file {}", path, e);
      return Optional.empty();
    }
  }

  private static String extractCopyrightHolderFromLegalFiles(List<SourceFileContent> sourceFiles) {
    String fallback = "";
    for (SourceFileContent file : sourceFiles) {
      String[] lines = file.content().split("\\R");
      for (String line : lines) {
        Matcher matcher = COPYRIGHT_LINE.matcher(line);
        if (matcher.matches()) {
          String normalized = normalizeCopyrightHolder(line);
          if (normalized.isBlank()) {
            continue;
          }
          if (isLikelyCopyrightHolder(normalized)) {
            return normalized;
          }
          if (fallback.isBlank()) {
            fallback = normalized;
          }
        }
      }
    }
    return fallback;
  }

  private static String normalizeCopyrightHolder(String text) {
    String value = safe(text).trim();
    if (value.isBlank()) {
      return "";
    }

    value = value.replaceFirst("(?i)^\\s*copyright(?:\\s*\\(c\\)|\\s*©)?\\s*", "").trim();
    value = value.replaceFirst("^\\s*\\d{4}(?:\\s*[-–—,/]\\s*\\d{2,4})*\\s+", "").trim();
    return value;
  }

  private static boolean isLikelyCopyrightHolder(String value) {
    String lower = safe(value).toLowerCase(Locale.ROOT);
    if (lower.isBlank()) {
      return false;
    }
    if (lower.length() > 120) {
      return false;
    }
    if (lower.contains("copyright notice")
        || lower.contains("shall mean")
        || lower.contains("the copyright owner")
        || lower.contains("terms and conditions")
        || lower.contains("this license")) {
      return false;
    }
    return true;
  }

  private static List<SourceFileContent> filterNoticeFiles(List<SourceFileContent> sourceFiles) {
    List<SourceFileContent> notices = new ArrayList<>();
    for (SourceFileContent file : sourceFiles) {
      if (isNoticeFile(file.path())) {
        notices.add(file);
      }
    }
    notices.sort(Comparator.comparing(SourceFileContent::path));
    return notices;
  }

  private static boolean isNoticeFile(String relativePath) {
    String path = safe(relativePath).replace('\\', '/');
    int slash = path.lastIndexOf('/');
    String fileName = slash >= 0 ? path.substring(slash + 1) : path;
    return fileName.toUpperCase(Locale.ROOT).startsWith("NOTICE");
  }

  private LicenseBundle resolveLicenseBundle(List<LicenseInfo> licenses,
                                             List<SourceFileContent> sourceFiles,
                                             Path localRepo) {
    String licenseName = licenses.isEmpty() ? "Unknown" : safe(licenses.get(0).name());
    String licenseUrl = licenses.isEmpty() ? "" : safe(licenses.get(0).url());

    Optional<SourceFileContent> localLicense = sourceFiles.stream()
        .filter(file -> isLicenseFile(file.path()))
        .findFirst();

    if (localLicense.isPresent()) {
      if (licenseName.isBlank() || "Unknown".equalsIgnoreCase(licenseName)) {
        licenseName = inferLicenseName(localLicense.get().content());
      }
      return new LicenseBundle(
          licenseName,
          localLicense.get().content(),
          licenseUrl,
          "Extracted from downloaded repository file: " + localLicense.get().path());
    }

    if (licenseName.isBlank()) {
      licenseName = "Unknown";
    }

    String resolution = "No LICENSE/COPYING file found in downloaded repository";
    if (localRepo == null || !Files.isDirectory(localRepo)) {
      resolution = "Repository is not downloaded locally at expected path";
    }
    return new LicenseBundle(licenseName, "", licenseUrl, resolution);
  }

  private static String inferLicenseName(String text) {
    String lower = safe(text).toLowerCase(Locale.ROOT);
    if (lower.contains("apache license") && lower.contains("version 2")) {
      return "Apache 2.0";
    }
    if (lower.contains("mit license")) {
      return "MIT";
    }
    if (lower.contains("gnu general public license")) {
      return "GPL";
    }
    if (lower.contains("bsd")) {
      return "BSD";
    }
    return "Unknown";
  }

  private static boolean isLicenseFile(String path) {
    String upper = path.toUpperCase(Locale.ROOT);
    return upper.contains("LICENSE") || upper.contains("COPYING");
  }

  private static boolean isInterestingLegalFile(String fileName) {
    String upper = fileName.toUpperCase(Locale.ROOT);
    return upper.contains("LICENSE") || upper.contains("NOTICE") || upper.contains("COPYING") || upper.contains("COPYRIGHT");
  }

  private static boolean isGeneratedLicenseReport(String fileName) {
    return safe(fileName).toUpperCase(Locale.ROOT).startsWith(REPORT_FILE_PREFIX);
  }

  private void appendMainComponentSection(StringBuilder sb, ResolvedDependency main) {
    sb.append("## Main Component\n\n");
    sb.append("- Component: ").append(main.coordinate().artifactId()).append(' ')
        .append(main.coordinate().version()).append("\n");
    sb.append("- Copyright Holder: ").append(safe(main.copyrightHolder())).append("\n");
    sb.append("- Source URL: ").append(safe(main.sourceUrl())).append("\n");
    sb.append("- License: ").append(safe(main.licenseName())).append("\n\n");

    sb.append("### Primary License Text\n\n");
    sb.append("```text\n");
    if (main.licenseText().isBlank()) {
      sb.append("License text not available in downloaded repository.\n");
    } else {
      sb.append(main.licenseText()).append("\n");
    }
    sb.append("```\n\n");

    sb.append("### Copyright Statement(s)\n\n");
    sb.append("```text\n");
    sb.append(formatCopyrightStatement(main.copyrightHolder())).append('\n');
    sb.append("```\n\n");

    sb.append("### Notice / Attribution\n\n");
    appendNoticeSection(sb, main.noticeFiles());
    sb.append("---\n\n");
  }

  private void appendFourthPartySection(StringBuilder sb,
                                        ResolvedDependency main,
                                        List<ResolvedDependency> fourthParty) {
    sb.append("## Fourth-Party Dependencies\n\n");
    if (fourthParty.isEmpty()) {
      sb.append("No fourth-party dependencies found for the selected component.\n\n");
      return;
    }

    String mainLicenseKey = normalizeLicenseKey(main.licenseName(), main.licenseText());
    Map<String, String> licenseReference = new LinkedHashMap<>();
    Map<String, Boolean> fullTextIncluded = new LinkedHashMap<>();
    licenseReference.put(mainLicenseKey, "main component license");
    fullTextIncluded.put(mainLicenseKey, !main.licenseText().isBlank());

    for (ResolvedDependency dep : fourthParty) {
      String depLicenseKey = normalizeLicenseKey(dep.licenseName(), dep.licenseText());
      String reference = licenseReference.get(depLicenseKey);
      boolean hasFullText = !dep.licenseText().isBlank();
      boolean alreadyIncluded = fullTextIncluded.getOrDefault(depLicenseKey, false);
      boolean mainTextIncluded = fullTextIncluded.getOrDefault(mainLicenseKey, false);

      sb.append("### Dependency: ").append(dep.coordinate().toDisplayString()).append("\n\n");
      sb.append("- Copyright Holder: ").append(safe(dep.copyrightHolder())).append("\n");
      sb.append("- Source URL: ").append(safe(dep.sourceUrl())).append("\n");
      sb.append("- License: ").append(safe(dep.licenseName())).append("\n");
      sb.append("\n");

      sb.append("#### License Text\n\n");
      sb.append("```text\n");
      if (hasFullText && !alreadyIncluded) {
        sb.append(dep.licenseText()).append("\n");
        licenseReference.put(depLicenseKey, dep.coordinate().toDisplayString());
        fullTextIncluded.put(depLicenseKey, true);
      } else if (depLicenseKey.equals(mainLicenseKey) && mainTextIncluded) {
        sb.append("Same as main component license.\n");
      } else if (alreadyIncluded && reference != null && !reference.isBlank()) {
        sb.append("Same as dependency license: ").append(reference).append(".\n");
      } else {
        sb.append("License text not available in downloaded repository.\n");
      }
      sb.append("```\n\n");

      sb.append("#### Notice / Attribution\n\n");
      appendNoticeSection(sb, dep.noticeFiles());
      sb.append("---\n\n");
    }
  }

  private static void appendNoticeSection(StringBuilder sb, List<SourceFileContent> noticeFiles) {
    if (noticeFiles == null || noticeFiles.isEmpty()) {
      sb.append("No notice.\n\n");
    } else {
      for (SourceFileContent notice : noticeFiles) {
        sb.append("File: ").append(notice.path()).append("\n\n");
        sb.append("```text\n");
        sb.append(notice.content()).append("\n");
        sb.append("```\n\n");
      }
    }
  }

  private static String normalizeLicenseKey(String licenseName, String licenseText) {
    String canonicalLicense = canonicalLicenseKey(licenseName, licenseText);
    if (!canonicalLicense.isBlank()) {
      return canonicalLicense;
    }

    String normalizedName = safe(licenseName).trim().toLowerCase(Locale.ROOT);
    if (!normalizedName.isBlank() && !"unknown".equals(normalizedName)) {
      return normalizedName;
    }

    String normalizedText = normalizeLicenseText(licenseText);
    if (!normalizedText.isBlank()) {
      return "text-" + Integer.toHexString(normalizedText.hashCode());
    }

    return normalizedName.isBlank() ? "unknown" : normalizedName;
  }

  private static String canonicalLicenseKey(String licenseName, String licenseText) {
    String name = safe(licenseName).toLowerCase(Locale.ROOT);
    boolean nameMissing = name.isBlank() || "unknown".equals(name);
    String text = nameMissing ? safe(licenseText).toLowerCase(Locale.ROOT) : "";

    if ((name.contains("apache") && name.contains("2"))
        || (text.contains("apache license") && text.contains("version 2"))) {
      return "apache-2.0";
    }
    if (name.contains("mit") || text.contains("mit license")) {
      return "mit";
    }
    if (name.contains("bsd") || text.contains("redistribution and use in source and binary forms")) {
      return "bsd";
    }
    if ((name.contains("cddl") && name.contains("gpl"))
        || (text.contains("common development and distribution license")
        && text.contains("gnu general public license"))) {
      return "cddl-1.1-gpl-2.0-classpath";
    }
    if (name.contains("classpath") && name.contains("gpl")) {
      return "gpl-2.0-classpath";
    }
    if ((name.contains("gpl") && name.contains("2")) || text.contains("gnu general public license")) {
      return "gpl-2.0";
    }
    return "";
  }

  private static String normalizeLicenseText(String licenseText) {
    if (licenseText == null || licenseText.isBlank()) {
      return "";
    }
    return licenseText
        .replace("\r", "")
        .replace('\u00A0', ' ')
        .replaceAll("[ \t]+", " ")
        .trim();
  }

  private static String formatCopyrightStatement(String copyrightHolder) {
    String value = safe(copyrightHolder).trim();
    if (value.isBlank()) {
      return "Copyright © Unknown";
    }
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("copyright")) {
      return value;
    }
    return "Copyright © " + value;
  }

  private static Document parseXml(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

    try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
      Document doc = factory.newDocumentBuilder().parse(is);
      doc.getDocumentElement().normalize();
      return doc;
    }
  }

  private static List<LicenseInfo> parseLicenses(Element root) {
    NodeList licensesNodes = root.getElementsByTagName("licenses");
    if (licensesNodes == null || licensesNodes.getLength() == 0) {
      return List.of();
    }

    List<LicenseInfo> licenses = new ArrayList<>();
    for (int i = 0; i < licensesNodes.getLength(); i++) {
      Node licensesNode = licensesNodes.item(i);
      if (!(licensesNode instanceof Element licensesEl)) {
        continue;
      }
      NodeList licenseNodes = licensesEl.getElementsByTagName("license");
      for (int j = 0; j < licenseNodes.getLength(); j++) {
        Node licenseNode = licenseNodes.item(j);
        if (!(licenseNode instanceof Element licenseEl)) {
          continue;
        }
        String name = firstChildText(licenseEl, "name").orElse("Unknown");
        String url = firstChildText(licenseEl, "url").orElse("");
        licenses.add(new LicenseInfo(name, url));
      }
    }

    licenses.sort(Comparator.comparing(LicenseInfo::name).thenComparing(LicenseInfo::url));
    return licenses;
  }

  private static Optional<Element> firstChildElement(Element parent, String tagName) {
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (!(node instanceof Element child)) {
        continue;
      }
      String localName = child.getLocalName();
      String nodeName = child.getNodeName();
      if (tagName.equals(localName) || tagName.equals(nodeName)) {
        return Optional.of(child);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> firstChildText(Element parent, String tagName) {
    return firstChildElement(parent, tagName)
        .map(Element::getTextContent)
        .map(String::trim)
        .filter(value -> !value.isBlank());
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  public record DependencyCoordinate(String groupId, String artifactId, String version) {
    public String toDisplayString() {
      return groupId + ":" + artifactId + ":" + version;
    }
  }

  public record DependencyResolutionKey(DependencyCoordinate coordinate, String fourthPartyParentRepoName) {}

  public record SourceFileContent(String path, String content) {}

  public record PomMetadata(String projectUrl, String scmUrl, String organizationName, List<LicenseInfo> licenses) {
    public static PomMetadata empty() {
      return new PomMetadata("", "", "", List.of());
    }
  }

  public record LicenseBundle(String licenseName, String licenseText, String licenseUrl, String resolutionNote) {}

  public record LicenseReportGenerationResult(
      List<Path> generatedFiles,
      List<String> includedDependencies,
      List<String> skippedDependencies) {}

  public record ResolvedDependency(
      DependencyCoordinate coordinate,
      String sourceUrl,
      String copyrightHolder,
      String licenseName,
      String licenseText,
      List<SourceFileContent> noticeFiles,
      String licenseUrl,
      String resolutionNote) {

    public static ResolvedDependency empty(DependencyNode node) {
      DependencyCoordinate c = new DependencyCoordinate(
          safe(node == null ? null : node.getGroupId()),
          safe(node == null ? null : node.getArtifactId()),
          safe(node == null ? null : node.getVersion()));
      return new ResolvedDependency(c, "", "Unknown", "Unknown", "", List.of(), "", "Invalid coordinate");
    }

    public ResolvedDependency withPreferredSourceUrl(String preferredSourceUrl) {
      return new ResolvedDependency(
          coordinate,
          preferredSourceUrl,
          copyrightHolder,
          licenseName,
          licenseText,
          noticeFiles,
          licenseUrl,
          resolutionNote);
    }
  }
}

package io.botsteve.dependencyanalyzer.service;

import static io.botsteve.dependencyanalyzer.utils.Utils.getRepositoriesPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.botsteve.dependencyanalyzer.model.DependencyNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LicenseAggregationServiceReportGenerationTest {

  private static final String OVERRIDES_FILE_PROPERTY = "dependency.analyzer.scm.overrides.file";

  private final Set<Path> rootsToCleanup = new LinkedHashSet<>();

  @AfterEach
  void cleanup() throws Exception {
    for (Path root : rootsToCleanup) {
      if (!Files.exists(root)) {
        continue;
      }
      try (Stream<Path> stream = Files.walk(root)) {
        stream.sorted(Comparator.reverseOrder()).forEach(path -> {
          try {
            Files.deleteIfExists(path);
          } catch (Exception ignored) {
          }
        });
      }
    }
    rootsToCleanup.clear();
    System.clearProperty(OVERRIDES_FILE_PROPERTY);
  }

  @Test
  void shouldWriteOneAggregatedReportPerSelectedThirdPartyIntoDedicatedLicenseDirectory() throws Exception {
    String projectName = "license-aggregation-" + UUID.randomUUID();
    Path repositoriesRoot = Path.of(getRepositoriesPath(projectName));
    rootsToCleanup.add(repositoriesRoot);

    DependencyNode first = dependency("org.liquibase", "liquibase-core", "4.33.0",
        "https://github.com/liquibase/liquibase");
    DependencyNode second = dependency("org.apache.commons", "commons-text", "1.13.1",
        "https://github.com/apache/commons-text");

    Path firstRepo = repositoriesRoot.resolve("3rd-party").resolve("liquibase");
    Path secondRepo = repositoriesRoot.resolve("3rd-party").resolve("commons-text");
    Path licensesDirectory = repositoriesRoot.resolve("licenses");
    writeLegalFile(firstRepo.resolve("LICENSE.txt"), apacheLicenseVariant("Liquibase Inc."));
    writeLegalFile(secondRepo.resolve("LICENSE.txt"), apacheLicenseVariant("The Apache Software Foundation"));

    LicenseAggregationService service = new LicenseAggregationService(projectName);
    LicenseAggregationService.LicenseReportGenerationResult result =
        service.generateAndStorePublicLicenseReports(Set.of(first, second));
    List<Path> generated = result.generatedFiles();

    assertEquals(2, generated.size());
    assertTrue(generated.stream().anyMatch(path -> path.equals(
        licensesDirectory.resolve("PUBLIC-LICENSES-NOTICE-org.liquibase-liquibase-core-4.33.0.md"))));
    assertTrue(generated.stream().anyMatch(path -> path.equals(
        licensesDirectory.resolve("PUBLIC-LICENSES-NOTICE-org.apache.commons-commons-text-1.13.1.md"))));
    generated.forEach(path -> assertTrue(Files.exists(path)));
    assertTrue(result.skippedDependencies().isEmpty());
  }

  @Test
  void shouldReferenceRepeatedApacheLicenseInsteadOfRepeatingFullTextForFourthPartyDependencies() throws Exception {
    String projectName = "license-dedup-" + UUID.randomUUID();
    Path repositoriesRoot = Path.of(getRepositoriesPath(projectName));
    rootsToCleanup.add(repositoriesRoot);

    DependencyNode root = dependency("org.liquibase", "liquibase-core", "4.33.0",
        "https://github.com/liquibase/liquibase");
    DependencyNode opencsv = dependency("com.opencsv", "opencsv", "5.11.2",
        "https://github.com/cygri/opencsv");
    DependencyNode snakeyaml = dependency("org.yaml", "snakeyaml", "2.4",
        "https://bitbucket.org/snakeyaml/snakeyaml");
    root.setChildren(List.of(opencsv, snakeyaml));

    Path rootRepo = repositoriesRoot.resolve("3rd-party").resolve("liquibase");
    Path fourthPartyRoot = repositoriesRoot.resolve("4th-party").resolve("liquibase");
    writeLegalFile(rootRepo.resolve("LICENSE.txt"), apacheLicenseVariant("Liquibase Inc."));
    writeLegalFile(fourthPartyRoot.resolve("opencsv").resolve("LICENSE"), apacheLicenseVariant("Kevin Kußmaul"));
    writeLegalFile(fourthPartyRoot.resolve("opencsv").resolve("NOTICE_Modifications"),
        "Changes to original Implementation");
    writeLegalFile(fourthPartyRoot.resolve("snakeyaml").resolve("LICENSE"), apacheLicenseVariant("SnakeYAML"));

    LicenseAggregationService service = new LicenseAggregationService(projectName);
    LicenseAggregationService.LicenseReportGenerationResult result = service.generateAndStorePublicLicenseReports(Set.of(root));
    List<Path> generated = result.generatedFiles();
    assertEquals(1, generated.size());

    String report = Files.readString(generated.get(0), StandardCharsets.UTF_8);
    assertTrue(report.contains("Same as main component license."));
    assertEquals(1, countOccurrences(report, "Apache License"));
    assertTrue(report.contains("## Main Component"));
    assertTrue(report.contains("#### Notice / Attribution"));
    assertTrue(report.contains("No notice."));
    assertTrue(report.contains("File: ./NOTICE_Modifications"));
    assertTrue(report.contains("Changes to original Implementation"));
    assertTrue(report.contains("---"));
    assertTrue(report.contains("```text"));
    assertTrue(result.skippedDependencies().isEmpty());
  }

  @Test
  void shouldIgnorePreviouslyGeneratedReportsWhenCollectingNoticeFiles() throws Exception {
    String projectName = "license-self-ingestion-" + UUID.randomUUID();
    Path repositoriesRoot = Path.of(getRepositoriesPath(projectName));
    rootsToCleanup.add(repositoriesRoot);

    DependencyNode root = dependency("org.liquibase", "liquibase-core", "5.0.1",
        "https://github.com/liquibase/liquibase");
    Path rootRepo = repositoriesRoot.resolve("3rd-party").resolve("liquibase");

    writeLegalFile(rootRepo.resolve("LICENSE.txt"),
        "Functional Source License\n\nCopyright © 2025 Liquibase Inc.\n");
    writeLegalFile(rootRepo.resolve("PUBLIC-LICENSES-NOTICE-liquibase-core-5.0.1.md"),
        "old generated report content should be ignored");

    LicenseAggregationService service = new LicenseAggregationService(projectName);
    LicenseAggregationService.LicenseReportGenerationResult result = service.generateAndStorePublicLicenseReports(Set.of(root));

    String report = Files.readString(result.generatedFiles().get(0), StandardCharsets.UTF_8);
    assertTrue(report.contains("No notice."));
    assertTrue(!report.contains("old generated report content should be ignored"));
    assertTrue(!report.contains("Copyright © Copyright ©"));
  }

  @Test
  void shouldSkipDependenciesWithMissingLocalRepositoryAndReportThemInSummary() throws Exception {
    String projectName = "license-skip-" + UUID.randomUUID();
    Path repositoriesRoot = Path.of(getRepositoriesPath(projectName));
    rootsToCleanup.add(repositoriesRoot);

    DependencyNode root = dependency("org.liquibase", "liquibase-core", "4.33.0",
        "https://github.com/liquibase/liquibase");
    DependencyNode existingChild = dependency("com.opencsv", "opencsv", "5.11.2",
        "https://github.com/cygri/opencsv");
    DependencyNode missingChild = dependency("org.example", "missing-lib", "1.0.0",
        "https://github.com/example/missing-lib");
    root.setChildren(List.of(existingChild, missingChild));

    Path rootRepo = repositoriesRoot.resolve("3rd-party").resolve("liquibase");
    Path fourthPartyRoot = repositoriesRoot.resolve("4th-party").resolve("liquibase");
    writeLegalFile(rootRepo.resolve("LICENSE.txt"), apacheLicenseVariant("Liquibase Inc."));
    writeLegalFile(fourthPartyRoot.resolve("opencsv").resolve("LICENSE"), apacheLicenseVariant("Kevin Kußmaul"));

    LicenseAggregationService service = new LicenseAggregationService(projectName);
    LicenseAggregationService.LicenseReportGenerationResult result = service.generateAndStorePublicLicenseReports(Set.of(root));

    assertEquals(1, result.generatedFiles().size());
    assertTrue(result.includedDependencies().contains("org.liquibase:liquibase-core:4.33.0"));
    assertTrue(result.includedDependencies().contains("com.opencsv:opencsv:5.11.2"));
    assertTrue(result.skippedDependencies().stream()
        .anyMatch(entry -> entry.contains("org.example:missing-lib:1.0.0")
            && entry.contains("Repository is not downloaded locally at expected path")));

    String report = Files.readString(result.generatedFiles().get(0), StandardCharsets.UTF_8);
    assertTrue(report.contains("Dependency: com.opencsv:opencsv:5.11.2"));
    assertTrue(!report.contains("Dependency: org.example:missing-lib:1.0.0"));
  }

  @Test
  void shouldUseCopyrightHolderAndNotLicenseDefinitionTextForFourthParty() throws Exception {
    String projectName = "license-copyright-holder-" + UUID.randomUUID();
    Path repositoriesRoot = Path.of(getRepositoriesPath(projectName));
    rootsToCleanup.add(repositoriesRoot);

    DependencyNode root = dependency("org.liquibase", "liquibase-core", "5.0.1",
        "https://github.com/liquibase/liquibase");
    DependencyNode commonsIo = dependency("commons-io", "commons-io", "2.20.0",
        "https://github.com/apache/commons-io");
    root.setChildren(List.of(commonsIo));

    Path rootRepo = repositoriesRoot.resolve("3rd-party").resolve("liquibase");
    Path fourthPartyRoot = repositoriesRoot.resolve("4th-party").resolve("liquibase").resolve("commons-io");

    writeLegalFile(rootRepo.resolve("LICENSE.txt"), "Copyright © 2025 Liquibase Inc.\n");
    writeLegalFile(fourthPartyRoot.resolve("LICENSE.txt"),
        "\"copyright notice that is included in or attached to the work\"\n"
            + "\"Licensor\" shall mean the copyright owner\n");
    writeLegalFile(fourthPartyRoot.resolve("NOTICE.txt"),
        "Apache Commons IO\nCopyright 2002-2025 The Apache Software Foundation\n");

    LicenseAggregationService service = new LicenseAggregationService(projectName);
    LicenseAggregationService.LicenseReportGenerationResult result = service.generateAndStorePublicLicenseReports(Set.of(root));
    String report = Files.readString(result.generatedFiles().get(0), StandardCharsets.UTF_8);

    assertTrue(report.contains("- Copyright Holder: The Apache Software Foundation"));
    assertTrue(!report.contains("- Copyright Holder: copyright notice that is included in or attached to the work"));
  }

  @Test
  void shouldApplyArtifactOverrideWhenResolvingSourceUrlInLicenseReport() throws Exception {
    String projectName = "license-source-override-" + UUID.randomUUID();
    Path repositoriesRoot = Path.of(getRepositoriesPath(projectName));
    rootsToCleanup.add(repositoriesRoot);

    Path overrides = repositoriesRoot.resolve("scm-overrides.properties");
    Files.writeString(overrides,
        "artifact.opencsv=https://git.code.sf.net/p/opencsv/source\n",
        StandardCharsets.UTF_8);
    System.setProperty(OVERRIDES_FILE_PROPERTY, overrides.toString());

    DependencyNode node = dependency("com.opencsv", "opencsv", "5.12.0",
        "https://github.com/example/opencsv");
    Path repo = repositoriesRoot.resolve("3rd-party").resolve("opencsv");
    writeLegalFile(repo.resolve("LICENSE"), apacheLicenseVariant("Kevin Kußmaul"));

    LicenseAggregationService service = new LicenseAggregationService(projectName);
    LicenseAggregationService.LicenseReportGenerationResult result = service.generateAndStorePublicLicenseReports(Set.of(node));
    String report = Files.readString(result.generatedFiles().get(0), StandardCharsets.UTF_8);

    assertTrue(report.contains("- Source URL: https://git.code.sf.net/p/opencsv/source"));
  }

  private static DependencyNode dependency(String groupId, String artifactId, String version, String scmUrl) {
    DependencyNode dependencyNode = new DependencyNode(groupId, artifactId, version);
    dependencyNode.setScmUrl(scmUrl);
    return dependencyNode;
  }

  private static void writeLegalFile(Path path, String content) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  private static int countOccurrences(String text, String token) {
    if (text == null || text.isBlank() || token == null || token.isBlank()) {
      return 0;
    }
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }

  private static String apacheLicenseVariant(String copyrightLine) {
    return "Apache License\n"
        + "Version 2.0, January 2004\n"
        + "http://www.apache.org/licenses/\n\n"
        + "Copyright " + copyrightLine + "\n";
  }
}

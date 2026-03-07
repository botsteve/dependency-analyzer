package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TableViewComponentFilterTest {

  @Test
  void shouldApplyFuzzyFilterCaseInsensitively() {
    assertTrue(TableViewComponent.matchesFilterText("org.slf4j:slf4j-api:2.0.13", "SLF4J"));
    assertFalse(TableViewComponent.matchesFilterText("org.junit:junit:4.13.2", "slf4j"));
  }

  @Test
  void shouldSupportFuzzySubsequenceAndTypoMatching() {
    assertTrue(TableViewComponent.matchesFilterText("org.yaml:snakeyaml:2.4", "snyml"));
    assertTrue(TableViewComponent.matchesFilterText("org.yaml:snakeyaml:2.4", "snakeyml"));
    assertFalse(TableViewComponent.matchesFilterText("org.apache:commons-io:2.17.0", "hibernate"));
  }

  @Test
  void shouldApplyExcludeFilterCaseInsensitively() {
    assertFalse(TableViewComponent.matchesExcludeText("org.slf4j:slf4j-api:2.0.13", "SLF4J"));
    assertTrue(TableViewComponent.matchesExcludeText("org.junit:junit:4.13.2", "slf4j"));
  }

  @Test
  void shouldFormatScopeSelectionWithCompactSuffixWhenLong() {
    Set<String> scopes = new LinkedHashSet<>();
    scopes.add("compile");
    scopes.add("runtime");
    scopes.add("test");

    assertEquals("compile (+2)", TableViewComponent.formatScopeSelection(scopes, 5));
    assertEquals("compile, runtime, test", TableViewComponent.formatScopeSelection(scopes, 100));
  }

  @Test
  void shouldApplyTextFiltersOnlyAtRootAndKeepChildrenScopeFiltered() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/botsteve/dependencyanalyzer/components/TableViewComponent.java"));
    assertTrue(source.contains("createFilteredRootTreeItem(node, fuzzyFilterText, excludeFilterText, scopes)"));
    assertTrue(source.contains("TreeItem<DependencyNode> childItem = createScopeFilteredTreeItem(child, scopes);"));
    assertTrue(source.contains("if (!textMatches) {"));
    assertFalse(source.contains("createFilteredTreeItem(child, filterText, scopes)"));
  }
}

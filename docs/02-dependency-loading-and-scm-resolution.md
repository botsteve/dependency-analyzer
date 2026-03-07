# Dependency Loading and SCM Resolution

This document explains how dependency metadata is loaded into the UI and how SCM/source repository URLs are resolved.

## 1) Loading pipeline overview

Primary classes:
- `DependencyLoadingTask` (JavaFX background task)
- `DependencyAnalyzerService` (entry orchestrator)
- `DependencyTreeAnalyzerService` (Maven tree path)
- `GradleDependencyAnalyzerService` (Gradle dependency path)
- `ScmEnrichmentService` and `ScmUrlFetcherService` (SCM URL enrichment)

Flow:
1. User selects a project via `ButtonsComponent`.
2. Project type is detected (`MAVEN` vs `GRADLE`).
3. `DependencyLoadingTask` runs in background and updates progress/message.
4. Dependency graph is transformed into `DependencyNode` entries for the tree-table.
5. SCM enrichment fills source URLs, with retry and override support.

## 2) Maven path

`DependencyAnalyzerService.getDependencies(...)` delegates to Maven path when `pom.xml` is detected.

`DependencyTreeAnalyzerService`:
- discovers modules,
- runs `maven-dependency-plugin:tree -DoutputType=json`,
- parses Maven output to extract JSON payload,
- combines root and module dependencies,
- filters module self-references from displayed third-party results.

## 3) Gradle path

`GradleDependencyAnalyzerService` handles Gradle-specific logic, including:
- dependency extraction strategy,
- Gradle wrapper inspection,
- JDK compatibility selection,
- fallback behavior when default JDK path is not viable.

## 4) SCM URL enrichment strategy

Enrichment combines multiple sources:
1. metadata-provided SCM URLs (when available),
2. repository heuristics,
3. runtime override mappings.

Override mappings are loaded from:
- `<baseDir>/config/scm-repositories-overrides.properties`
or explicit system property path:
- `-Ddependency.analyzer.scm.overrides.file=/absolute/path/...`

The overrides file is hot-reloaded at runtime on timestamp change.

## 5) Why this split exists

Maven and Gradle dependency data differ in format and toolchain requirements. The split keeps each path deterministic while unifying output as `DependencyNode` so UI logic stays consistent.

# JdkDownloadTask Deep Dive

This document explains how `JdkDownloadTask` works internally, the algorithms and heuristics it uses, and the runtime guarantees it provides.

## 1) Why this task exists

`JdkDownloadTask` automates acquisition of required JDKs (`JAVA8_HOME`, `JAVA11_HOME`, `JAVA17_HOME`, `JAVA21_HOME`) so downstream dependency build tasks can run with compatible toolchains.

Without this task, users would manually locate/install each JDK and keep settings synchronized.

## 2) High-level pipeline

For each requested JDK setting key:

1. Resolve host OS + architecture.
2. Derive Adoptium API URL.
3. Download archive with streamed progress updates.
4. Extract safely into `downloaded_jdks/<SETTING_KEY>`.
5. Discover the effective `JAVA_HOME` root.
6. Persist/update `config/env-settings.properties`.

The task runs inside JavaFX `Task.call()` to keep UI responsive.

## 3) Core algorithms and policies

### 3.1 Requested-key normalization algorithm

Method: `resolveEffectiveSettings(Set<String> requested)`

Algorithm:
- Uses canonical ordered list `REQUIRED_JDK_SETTINGS`.
- If user request set is empty → returns all required keys.
- Otherwise returns intersection while preserving canonical order.

Why:
- deterministic progress ordering,
- stable logs and reproducible behavior.

### 3.2 OS/architecture normalization

Methods:
- `normalizeOs(String osName)`
- `normalizeArchitecture(String architecture)`

Algorithm:
- maps many OS/arch aliases to normalized values expected by Adoptium API.
- unsupported values fail fast with `DependencyAnalyzerException`.

Why:
- avoids hidden fallback behavior,
- ensures API requests are explicit and valid.

### 3.3 JDK8-on-ARM architecture policy

Method: `resolveDownloadArchitecture(int version, String hostArch)`

Policy:
- if version == 8, force `x64` download (API availability constraint)
- otherwise use host architecture.

This is communicated via:
- `buildDownloadMessage(...)`
- `java8X64Explanation()`
- task logs.

### 3.4 Weighted progress aggregation algorithm

Methods:
- `computeOverallProgressFraction(int jdkIndex, int totalJdks, double withinJdkFraction)`
- `updateOverallProgress(...)`

Concept:
- Each JDK contributes equal segment `1 / totalJdks`.
- Intra-JDK phases are weighted:
  - download phase weight: `DOWNLOAD_PHASE_WEIGHT`
  - extract phase weight: `EXTRACT_PHASE_WEIGHT`
- final fraction = `(jdkIndex + withinJdkFraction) / totalJdks`.

Why:
- smooth UX across multiple downloads,
- avoids progress jumps from per-JDK resets.

### 3.5 Safe extraction algorithm

Archive extraction path validates entries to prevent zip-slip/path traversal.

Core safety checks:
- normalize target path,
- assert extracted path remains under intended install directory,
- reject malicious entry paths.

Why:
- protects against crafted archive names escaping install root.

### 3.6 Settings persistence algorithm

After each successful install:
- compute final Java home path,
- update in-memory `Properties`,
- persist via `Utils.saveSettings(...)` to `config/env-settings.properties`.

`Utils` includes migration logic:
- if legacy `<baseDir>/env-settings.properties` exists and new config file does not,
- it is moved automatically to `<baseDir>/config/env-settings.properties`.

## 4) Network model

- Uses `HttpClient` with redirect following and connect timeout.
- Proxy is configured through `ProxyUtil.configureProxyIfEnvAvailable()` before network operations.
- Download progress uses streaming wrappers and byte counters for percent/status updates.

## 5) Error model

Errors are raised as `DependencyAnalyzerException` with actionable context:
- unsupported OS/arch,
- HTTP download failures,
- extraction failures,
- settings write failures.

In UI, task failure flows through `ButtonsComponent.runManagedTask(...)` and is displayed in an alert while releasing progress UI state.

## 6) Performance characteristics

- Sequential per-JDK processing (simpler failure semantics and disk pressure control).
- Streamed I/O for bounded memory usage during large archive downloads.
- Weighted progress avoids expensive cross-thread synchronization patterns.

## 7) Related classes

- `Utils` — settings load/save + base directory logic.
- `LogUtils` — runtime base directory/log path.
- `ButtonsComponent` — task lifecycle, initial message/visibility, result summary.

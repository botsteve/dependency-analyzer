# Dependency Analyzer

A powerful JavaFX desktop application for analyzing **Maven** and **Gradle** project dependencies, resolving source code repositories, and rebuilding dependencies from source. Designed to help developers inspect dependency trees, fetch source code (Git), and compile projects using the correct JDK versions.

<img src="img/app.png" alt="Overview" width="800">

---

## Features

- **Multi-Build-System Support**: Analyze dependencies from both **Maven** (`pom.xml`) and **Gradle** (`build.gradle` / `build.gradle.kts`) projects.
- **Dependency Tree Visualization**: Graphically inspect the full dependency tree in a sortable, filterable tree-table.
- **Scope Column**: Each dependency displays its scope (e.g., `compile`, `runtime`, `test` for Maven; `implementation`, `api`, `compileOnly` for Gradle).
- **Scope Filter Dropdown**: Filter the dependency tree by scope using a dynamic dropdown populated from the loaded project.
- **Text Exclusion Filter**: Quickly exclude dependencies matching a text pattern.
- **Source Resolution**: Automatically resolves Git/SCM URLs for dependencies from Maven Central or Gradle module metadata.
- **Selective Download**: Choose specific dependencies and download their source code repositories.
- **Cross-Version Building**: Automatically builds dependencies using the correct JDK version (Java 8, 11, 17, 21) via Maven Toolchains or Gradle Wrapper.
- **Auto JDK Bootstrap**: Download and configure required JDKs (8/11/17/21) from Adoptium directly from the UI for the current OS/CPU architecture.
- **Smart JDK Detection (Gradle)**: Reads the Gradle Wrapper version from `gradle-wrapper.properties` and selects the compatible JDK automatically — no manual configuration needed.
- **Verbose Build Logging**: All build tool invocations (Maven `-X`, Gradle `--info`, Ant `-verbose`) produce detailed output for troubleshooting.
- **Improved UI & Task Visibility**: 
  - **Project Context**: Displays the currently opened project name in the toolbar for quick reference.
  - **Redesigned Loading State**: A prominent, thick, blue-gradient progress bar with vertically stacked status messages for better readability.
  - **Space Recovery**: UI automatically collapses the progress area when tasks finish to maximize table space.
- **Task Management**: Prevents conflicting operations (e.g., building while downloading) from running simultaneously.
- **Cross-Platform**: Runs on Windows, macOS, and Linux as a single executable JAR.

---

## 📚 Detailed Component Documentation

For deep implementation details, see the docs series:

- [01 - Native Runtime and Startup Flow](docs/01-native-runtime-and-startup.md)
- [02 - Dependency Loading and SCM Resolution](docs/02-dependency-loading-and-scm-resolution.md)
- [03 - Dependency Download and Checkout Pipeline](docs/03-dependency-download-and-checkout.md)
- [04 - JDK Bootstrap and Build Pipeline](docs/04-jdk-bootstrap-and-build-pipeline.md)
- [05 - CI Workflows and Release Artifacts](docs/05-ci-workflows-and-release-artifacts.md)
- [06 - JdkDownloadTask Deep Dive](docs/06-jdk-download-task-deep-dive.md)
- [07 - Release Process (Reusable)](docs/07-release-process.md)
- [08 - Release Notes for v2.0.0](docs/release-notes/08-release-notes-2.0.0.md)
- [09 - Release Notes for v2.0.1](docs/release-notes/09-release-notes-2.0.1.md)

---

## 🚀 Getting Started

### Prerequisites

1. **Java 21+**: JDK 21 or higher is required to run the application itself.
   - *You do NOT need a special JavaFX-bundled JDK — the application includes JavaFX libraries.*
2. **Maven**: A local installation of Apache Maven (3.9.x recommended). Ensure `MAVEN_HOME` is set.
3. **Target JDKs**: To build older dependencies, install the relevant JDKs (e.g., JDK 8, JDK 11, JDK 17) and configure their paths in the app settings.
4. **Git**: Required for cloning dependency source code repositories.

### 🛠️ Installation

1. **Clone the Repository**:
   ```bash
   git clone <repository-url>
   cd dependency-analyzer
   ```

2. **Build the Application**:
   ```bash
   mvn clean package
   ```

3. **Locate the JAR**:
   The executable file is generated at:
   ```
   target/dependency-analyzer.jar
   ```

---

## 🏃 Running the Application

```bash
java -jar target/dependency-analyzer.jar
```

### 🛠️ Building Native Images

This repository supports GluonFX native builds via the `gluonfx-native` profile.

Native builds use `io.botsteve.dependencyanalyzer.NativeEntryPoint` and require GraalVM JDK 21 with `native-image` installed.

#### Gluon Native (`-Pgluonfx-native`)

```bash
GRAALVM_HOME=/path/to/graalvm mvn -Pgluonfx-native -DskipTests -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build
```

Output binary (macOS host build):
- `target/gluonfx/aarch64-darwin/dependency-analyzer`

#### Native metadata and behavior notes

- Native metadata is maintained under:
  - `src/main/resources/META-INF/native-image`
  - `src/main/resources/META-INF/substrate/config`
- Keep substrate metadata in strict JSON form and avoid empty platform-specific override files (for example `jniconfig-aarch64-darwin.json` as `[]`), because Gluon merge output can become invalid in CI.
- These files provide reflection/JNI/resource configuration required for JavaFX, logging, and JGit-related native runtime paths.
- In native runtime, dependency download uses a native-safe CLI git path to avoid JGit reflection pitfalls during clone/fetch/checkout.
- You may still see JavaFX unnamed-module warnings during startup; these are expected for this packaging shape when stage startup succeeds.

---


## ⚙️ Configuration

### First-Time Setup: JDK Paths

1. Go to `Settings` → `Environment Settings`.
2. Use the **Browse** buttons to set paths for each JDK:
   - **JAVA8_HOME** — e.g., `/Library/Java/JavaVirtualMachines/jdk1.8.0_xxx.jdk/Contents/Home`
   - **JAVA11_HOME** — e.g., `/Library/Java/JavaVirtualMachines/jdk-11.jdk/Contents/Home`
   - **JAVA17_HOME** — e.g., `/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`
   - **JAVA21_HOME** — e.g., `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`
3. Click **Save**. Settings are persisted in `config/env-settings.properties` under the application's base directory.

> Tip: You can also use the toolbar button **Download Required JDKs** to automatically download JDK 8/11/17/21 for your platform and update `config/env-settings.properties` for you.
>
> Download location: JDKs are placed next to the app execution base directory under `downloaded_jdks/`, each in its own folder (`JAVA8_HOME`, `JAVA11_HOME`, `JAVA17_HOME`, `JAVA21_HOME`).
>
> During JDK bootstrap, the app shows live download/extraction progress in the progress bar and status label (percent updates), and logs progress checkpoints to `logs/dependency-analyzer.log`.
>
> While JDK bootstrap is running, the rest of the UI actions are temporarily locked to avoid conflicting operations.
>
> Note for ARM hosts: Java 8 is downloaded as **x64** because an aarch64 binary is not available from this API endpoint.

> **Required environment variables**: Ensure `JAVA_HOME` and `MAVEN_HOME` are set on your system. The application warns you if they are missing when opening a project.

### Smart JDK Detection for Gradle

When opening a Gradle project, the application automatically reads `gradle/wrapper/gradle-wrapper.properties` to determine the Gradle version, and selects the best-compatible JDK:

| Gradle Version | JDK Used       |
|----------------|----------------|
| 4.x            | `JAVA8_HOME`   |
| 5.x            | `JAVA11_HOME`  |
| 6.x            | `JAVA11_HOME`  |
| 7.0 – 7.5      | `JAVA17_HOME`  |
| 7.6+           | `JAVA17_HOME`  |
| 8.0 – 8.4      | `JAVA17_HOME`  |
| 8.5+           | `JAVA21_HOME`  |

If the detected JDK fails, the app automatically falls back to trying all other configured JDKs.

### SCM Redirect Overrides (Runtime Editable)

Some upstream SCM endpoints (for example `gitbox.apache.org`) may redirect to non-clone URLs and fail in JGit.  
The app now supports an external, runtime-editable override file so you can fix those mappings without rebuilding.

- Default file path: `<app-base-dir>/config/scm-repositories-overrides.properties`
- Optional custom path: `-Ddependency.analyzer.scm.overrides.file=/absolute/path/to/file.properties`
- External-only mode:
  - this external file is the only SCM rewrite source used at runtime
  - on first run, if missing, it is created from the JAR resource template `src/main/resources/config/scm-repositories-overrides.properties`
  - there is no built-in hardcoded fallback mapping list in code
  - malformed content still fails fast during SCM resolution/reload

Supported key formats in that file:

- `artifact.<artifactId>=<repoUrl>`
- `group.<groupId>=<repoUrl>`
- `<artifactId>=<repoUrl>` (legacy shorthand; interpreted as artifact mapping)

Example:

```properties
artifact.commons-collections=https://github.com/apache/commons-collections
artifact.commons-text=https://github.com/apache/commons-text
group.org.yaml=https://github.com/snakeyaml/snakeyaml
```

The file is reloaded automatically when its timestamp changes, so updates apply during runtime workflows (no rebuild needed).
For safe runtime updates, write to a temp file and atomically move it into place.

### SCM Failure Taxonomy (Operational)

Downloader/build status output uses classified failure codes to simplify troubleshooting:

- `REDIRECT_BLOCKED` — remote SCM endpoint redirects to a non-cloneable target or violates redirect policy.
- `AUTH_FAILURE` — credentials/permissions rejected by remote.
- `DNS_FAILURE` — repository host could not be resolved.
- `TIMEOUT` — network operation exceeded timeout budget.

When these appear, verify SCM overrides first (`dependency.analyzer.scm.overrides.file`), then network/proxy settings.

---

## 🌐 Proxy Configuration

If you are behind a corporate proxy, the application reads **`https_proxy` / `HTTPS_PROXY`** first, then falls back to **`http_proxy` / `HTTP_PROXY`**. This is used when downloading source code repositories and when downloading required JDK archives.

You can bypass proxy for specific hosts by setting **`no_proxy` / `NO_PROXY`** (or `noproxy`) as a comma-separated list (for example: `localhost,.internal.company.com,*.svc.cluster.local`).

### How to Configure

Set the `http_proxy` environment variable **before launching the application**:

#### macOS / Linux

```bash
export http_proxy=http://your-proxy-host:8080
export no_proxy=localhost,.internal.company.com
java -jar target/dependency-analyzer.jar
```

Or add it permanently to your shell profile (`~/.zshrc`, `~/.bashrc`):

```bash
export http_proxy=http://your-proxy-host:8080
export https_proxy=http://your-proxy-host:8080
```

#### Windows (Command Prompt)

```cmd
set http_proxy=http://your-proxy-host:8080
set no_proxy=localhost,.internal.company.com
java -jar target/dependency-analyzer.jar
```

#### Windows (PowerShell)

```powershell
$env:http_proxy = "http://your-proxy-host:8080"
java -jar target\dependency-analyzer.jar
```

### How it Works

When the application downloads source code (via the **Download 3rd Party** or **Download 4th Party** button), it checks for the `http_proxy` environment variable. If present, it:

1. Parses the host and port from the URL (e.g., `http://proxy.example.com:8080`).
2. Configures a global Java `ProxySelector` that routes **all** HTTP/HTTPS connections through the proxy.
3. The proxy is used for Git clone operations and any network requests made by the application.

> **Note**: If `http_proxy` is not set, the application connects directly without a proxy. The scheme prefix (`http://`) is optional in the environment variable value — the app will add it automatically if missing.

### Maven Proxy

For Maven-specific proxy settings (used when resolving dependencies), configure your proxy in `~/.m2/settings.xml`:

```xml
<settings>
  <proxies>
    <proxy>
      <id>corporate-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>your-proxy-host</host>
      <port>8080</port>
      <!-- Optional: -->
      <username>proxyuser</username>
      <password>proxypass</password>
      <nonProxyHosts>localhost|*.internal.corp</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
```

### Gradle Proxy

For Gradle projects, configure proxy in `~/.gradle/gradle.properties`:

```properties
systemProp.http.proxyHost=your-proxy-host
systemProp.http.proxyPort=8080
systemProp.https.proxyHost=your-proxy-host
systemProp.https.proxyPort=8080
# Optional:
# systemProp.http.proxyUser=proxyuser
# systemProp.http.proxyPassword=proxypass
# systemProp.http.nonProxyHosts=localhost|*.internal.corp
```

---

## 📖 Usage Guide

### 1. Open a Project

Click **Open Directory** and select the root directory of the project to analyze:
- **Maven**: Must contain a `pom.xml`
- **Gradle**: Must contain a `build.gradle` or `build.gradle.kts` (with or without a Gradle Wrapper)

The tool parses the project and displays the dependency tree with columns:

| Column       | Description                                        |
|--------------|----------------------------------------------------|
| ☑ Select     | Checkbox to select dependencies for download/build |
| Dependency   | `groupId:artifactId:version` tree hierarchy        |
| Scope        | Dependency scope (`compile`, `implementation`, etc) |
| SCM URL      | Resolved Git/source code repository URL            |
| Checkout Tag | Git tag to checkout for the dependency version     |
| Build With   | JDK version used to build this dependency          |

### 2. Filter & Select

- **Exclude filter**: Type in the "Exclude" text box to hide dependencies matching that text.
- **Scope filter**: Use the "Scope" dropdown to show only dependencies of a specific scope (e.g., only `compile` or only `test`).
- **Select All**: Check the "Select All" checkbox to select/deselect all visible dependencies.

Click **Download 3rd Party**. The tool clones selected direct 3rd-party SCM repositories into:

`downloaded_repos/<project_name>/3rd-party/<repo_name>/`

Click **Download 4th Party** to clone 4th-party repos into a parent-grouped structure:

`downloaded_repos/<project_name>/4th-party/<third_party_repo_name>/<fourth_party_repo_name>/`

This keeps both 3rd-party and 4th-party repositories organized per project and per owning dependency.

### 4. Build

Click **Build Selected**. The tool:
1. Inspects each downloaded project to detect the required Java version.
2. Generates a `toolchains.xml` (for Maven projects).
3. Triggers the build using the correct JDK:
   - **Maven**: `mvn clean install` with `-X` (verbose/debug output)
   - **Gradle**: `./gradlew clean build` with `--info` (verbose output)
   - **Ant**: `ant` with `-verbose` flag
4. Results are shown as a summary of successful and failed builds.

### 5. Context Menu

Right-click on any dependency to access additional options via the context menu.

---

## 🏗️ Architecture

```
io.botsteve.dependencyanalyzer/
├── views/                  # JavaFX application views
│   └── MainAppView.java        # Main application window
├── components/             # UI components
│   ├── TableViewComponent.java  # Tree-table + filter/scope controls
│   ├── ColumnsComponent.java    # Column definitions (scope, SCM, etc)
│   ├── ButtonsComponent.java    # Toolbar buttons
│   ├── CheckBoxComponent.java   # Select all checkbox
│   ├── ContextMenuComponent.java # Legacy context menu helper
│   ├── MenuComponent.java       # Menu bar
│   └── ProgressBoxComponent.java # Progress bar
├── service/                # Business logic
│   ├── DependencyAnalyzerService.java      # Maven dependency analysis orchestrator
│   ├── DependencyTreeAnalyzerService.java  # Maven CLI tree parser
│   ├── GradleDependencyAnalyzerService.java # Gradle dependency analysis + JDK detection
│   ├── MavenInvokerService.java            # Maven Invoker API wrapper
│   ├── ScmUrlFetcherService.java           # CycloneDX SCM URL resolver
│   ├── ScmEnrichmentService.java           # POM-based SCM enrichment + retry
│   └── LicenseAggregationService.java      # 3rd/4th-party license report generation
├── tasks/                  # Background tasks (JavaFX Task)
│   ├── DependencyLoadingTask.java    # Load dependency tree
│   ├── DependencyDownloaderTask.java # Download source repos
│   ├── BuildRepositoriesTask.java    # Build downloaded repos
│   └── CheckoutTagsTask.java        # Resolve checkout tag by dependency version
├── model/                  # Data models
│   ├── DependencyNode.java          # Dependency with scope
│   ├── EnvSetting.java              # Settings key-value pair
│   └── CollectingOutputHandler.java # Maven output collector
├── utils/                  # Utilities
│   ├── Utils.java                   # Settings, version comparison
│   ├── FxUtils.java                 # JavaFX helpers
│   ├── ProxyUtil.java               # HTTP proxy configuration
│   ├── JavaVersionResolver.java     # JDK version resolution
│   ├── ScmRepositories.java         # External SCM override resolution
│   ├── ScmUrlUtils.java             # SCM canonicalization + repo naming
│   └── OperationStatus.java         # Structured status formatting
├── exception/              # Custom exceptions
│   └── DependencyAnalyzerException.java
└── logging/                # Logging configuration
    └── TextAreaAppender.java
```

---

## 🔧 Troubleshooting

| Issue | Solution |
|-------|---------|
| `java` or `mvn` not found | Ensure `JAVA_HOME` and `MAVEN_HOME` are in your system PATH |
| Gradle: "Unsupported class file major version" | The app auto-detects the compatible JDK from the Gradle Wrapper version. Ensure you have the appropriate JDK configured in Settings. |
| macOS M1/M2 build failures | Some older dependencies (e.g., Hibernate Validator with JRuby) may need an x86_64 JDK via Rosetta. |
| Proxy connection errors | Set the `http_proxy` environment variable before launching. See [Proxy Configuration](#-proxy-configuration). |
| No dependencies found | Ensure the project compiles. For Gradle, ensure `dependencies` configurations are declared. |
| Settings not saved | Check that `config/env-settings.properties` is writable under the app base directory. |

---

## 📋 Verbose Build Logging

All build tool commands run with verbose flags for detailed diagnostics:

| Build Tool | Flag         | Effect                                              |
|------------|--------------|-----------------------------------------------------|
| Maven      | `-X` (debug) | Full dependency resolution, plugin configs, POM      |
| Gradle     | `--info`     | Task execution, dependency resolution, build details |
| Ant        | `-verbose`   | Target/task execution, property resolution           |

All output is logged to the console at `INFO` level and visible in the application log.

---

## ✅ Verification Commands

Use these commands to validate the current implementation and packaging gates:

```bash
mvn test
mvn clean package
mvn -B verify --file pom.xml
GRAALVM_HOME=/path/to/graalvm mvn -Pgluonfx-native -DskipTests -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build
```

Single-test execution shortcuts:

```bash
mvn -Dtest=UtilsTest test
mvn -Dtest=UtilsTest#testCollectLatestVersions test
```

Targeted regression checks for filtering/output popup:

```bash
mvn -Dtest=TableViewComponentFilterTest,ColumnsComponentOutputPopupTest,DependencyAnalyzerTest test
```

GitHub Actions (`.github/workflows/maven.yml`) now runs:
- Matrix JVM verification (`mvn -B verify --file pom.xml`) on Ubuntu, Windows, and macOS.
- A dedicated macOS Gluon native build (`mvn -B -DskipTests -Pgluonfx-native -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build`) with binary artifact upload.

Gluon CI resilience behavior:
- Workflow preflights the Gluon JavaFX static SDK endpoint before running `gluonfx:build` using `javafx.static.sdk.version` (fallback: `javafx.version`).
- If endpoint is confirmed unavailable (`403`/`404`), non-release runs are skipped with warnings.
- If probe is inconclusive (timeouts/5xx/network), non-release runs still attempt build.
- On release workflows, native jobs are best-effort: build failures log warnings and release continues.
- Native artifacts are published only when their corresponding native build succeeds.
- When available, Gluon build step retries up to 3 attempts with incremental backoff.

## 🤖 Agent Development Guide

If you are using coding agents in this repository, use `AGENTS.md` as the operational contract.
It includes authoritative build/test/run commands, single-test patterns, code style guidance, and repo-specific validation expectations.

Policy file status (checked by path):

- `.cursorrules` — not present
- `.cursor/rules/` — not present
- `.github/copilot-instructions.md` — not present

---

## 📝 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.

Developed by **Rusen Stefan @ Oracle**.

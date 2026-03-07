# CI Workflows and Release Artifacts

This repository uses a single GitHub Actions workflow at `.github/workflows/maven.yml`.

## 1) Trigger model

Workflow triggers:
- push to `main`
- pull requests targeting `main`
- published release
- manual (`workflow_dispatch`)

## 2) Jobs

### `build`
Matrix JVM validation on:
- Ubuntu
- Windows
- macOS

Command:
- `mvn -B verify --file pom.xml`

Artifact:
- shaded JAR (`target/dependency-analyzer.jar`) uploaded once from Ubuntu.

Release behavior:
- JAR is attached to GitHub Release on `release.published`.

### `gluon-native-macos`
Builds Gluon native executable using `gluonfx-native` profile.

Command:
- `mvn -B -DskipTests -Pgluonfx-native -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build`

Artifact:
- `target/gluonfx/aarch64-darwin/dependency-analyzer`

Availability behavior:
- Before running Gluon build, workflow probes Gluon static SDK endpoint for `javafx.static.sdk.version` (fallback: `javafx.version`).
- On `push` / `pull_request` / `workflow_dispatch`: when endpoint is confirmed unavailable (`403`/`404`), Gluon job is skipped with warnings so core CI remains actionable.
- On `push` / `pull_request` / `workflow_dispatch`: inconclusive probe results (timeout/5xx/network) still proceed with Gluon build attempt.
- On `release`: endpoint unavailability skips Gluon native build, and inconclusive probe still attempts Gluon build.
- On `release`: if native build ultimately fails, workflow continues and logs warnings (best-effort native artifacts).
- When endpoint is available, build command is retried up to 3 times with incremental backoff for transient network failures.

## 3) Native CI strategy

- `gluonfx-native` is the only native CI path.
- This keeps native validation aligned with the JavaFX-focused packaging route used in this repository.

## 4) Release artifacts

On release publication, workflow uploads:
- shaded JAR (required)
- macOS Gluon native binary (optional, best effort)

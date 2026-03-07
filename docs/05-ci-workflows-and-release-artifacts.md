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

### `native-macos`
Builds Graal native executable using `native` profile.

Command:
- `mvn -B package -Pnative -DskipTests`

Artifact:
- `target/dependency-analyzer`

### `gluon-native-macos`
Builds Gluon native executable using `gluonfx-native` profile.

Command:
- `mvn -B -DskipTests -Pgluonfx-native -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build`

Artifact:
- `target/gluonfx/aarch64-darwin/dependency-analyzer`

## 3) Why both native jobs exist

- `native` profile validates the baseline Graal native plugin path.
- `gluonfx-native` validates the JavaFX-focused native packaging route used for robust UI startup behavior.

Keeping both jobs gives earlier signal when one native strategy regresses.

## 4) Release artifacts

On release publication, workflow uploads:
- shaded JAR
- macOS Graal native binary
- macOS Gluon native binary

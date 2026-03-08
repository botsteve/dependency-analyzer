# Release Process (Reusable for Any Version)

This document defines a practical, reproducible release flow for this repository’s Maven + GitHub Actions setup.

It is intentionally generic and can be used for **any release version** (`x.y.z`).

---

## 1) Preconditions

Before cutting a release:

1. Working tree is clean.
2. `main` contains all intended release changes.
3. Local validation passes:
   - `mvn test`
   - `mvn -B verify --file pom.xml`
   - `GRAALVM_HOME=/path/to/graalvm mvn -Pgluonfx-native -DskipTests -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build`
4. Release notes draft is ready.

---

## 2) Version inputs

Current project versioning pattern is snapshot-based (`x.y.z-SNAPSHOT`).

For a release, define:

- `RELEASE_VERSION` (for example `2.0.0`)
- `NEXT_DEVELOPMENT_VERSION` (for example `2.0.1-SNAPSHOT` or `2.1.0-SNAPSHOT`)

### How version bumping works (patch / minor / major)

This project follows semantic versioning style (`MAJOR.MINOR.PATCH`):

- **PATCH** bump (`x.y.z` -> `x.y.(z+1)`):
  - bug fixes, no breaking behavior changes,
  - example: `2.0.1` -> `2.0.2`.
- **MINOR** bump (`x.y.z` -> `x.(y+1).0`):
  - backward-compatible features or notable enhancements,
  - example: `2.0.2` -> `2.1.0`.
- **MAJOR** bump (`x.y.z` -> `(x+1).0.0`):
  - breaking changes (API/behavior/config expectations that require user changes),
  - example: `2.1.5` -> `3.0.0`.

When using `maven-release-plugin`, you choose both values explicitly:

- `-DreleaseVersion=<MAJOR.MINOR.PATCH>`
- `-DdevelopmentVersion=<next-version>-SNAPSHOT`

Examples:

- Patch release:
  - `releaseVersion=2.0.2`
  - `developmentVersion=2.0.3-SNAPSHOT`
- Minor release:
  - `releaseVersion=2.1.0`
  - `developmentVersion=2.2.0-SNAPSHOT` (or `2.1.1-SNAPSHOT` if you keep a maintenance line)
- Major release:
  - `releaseVersion=3.0.0`
  - `developmentVersion=3.1.0-SNAPSHOT` (or `3.0.1-SNAPSHOT` for immediate patch line)

---

## 3) Maven release plugin path (recommended)

`pom.xml` configures `maven-release-plugin` with tag name format based on project version.

### Generic command template

```bash
mvn -B release:clean
mvn -B release:prepare \
  -DpushChanges=false
  -DreleaseVersion=${RELEASE_VERSION} \
  -DdevelopmentVersion=${NEXT_DEVELOPMENT_VERSION}
mvn -B release:perform
```

### Verbose / troubleshooting mode

If `release:prepare` appears to hang, run with debug output first:

```bash
mvn -X -B release:clean release:prepare \
  -DdryRun=true \
  -DpushChanges=false \
  -DreleaseVersion=${RELEASE_VERSION} \
  -DdevelopmentVersion=${NEXT_DEVELOPMENT_VERSION}
```

Then run the real prepare command (without dry-run) once output is clean.

Common hang causes:

1. Invalid SCM `developerConnection` in `pom.xml`.
2. SSH authentication prompt waiting in background (missing key / agent not loaded).
3. Missing GitHub auth for the push target.

Quick checks:

```bash
git remote -v
ssh -T git@github.com
git push --dry-run origin main
```

`maven-release-plugin` uses SCM info from `pom.xml`, so `git remote -v` and `<scm>` should point to the same repository.

### Example (2.0.0)

```bash
mvn -B release:clean
mvn -B release:prepare -DreleaseVersion=2.0.0 -DdevelopmentVersion=2.0.1-SNAPSHOT
mvn -B release:perform
```

### What `release:prepare` does

1. rewrites `pom.xml` from snapshot to release version,
2. creates release commit,
3. creates release tag,
4. rewrites `pom.xml` to next snapshot,
5. creates post-release commit.

---

## 4) GitHub Release + CI artifact publishing

Workflow: `.github/workflows/maven.yml`

It is designed to publish artifacts on release events.

Expected artifacts:

- shaded JAR: `target/dependency-analyzer.jar`
- Gluon native binary (macOS host build): `target/gluonfx/aarch64-darwin/dependency-analyzer`

Typical flow:

1. push release commits + tag,
2. publish GitHub Release for the created tag,
3. verify workflow status and attached artifacts.

---

## 5) Manual fallback (without release plugin)

If manual control is preferred:

1. set `pom.xml` to `${RELEASE_VERSION}`,
2. run full validation commands,
3. commit release version change,
4. create git tag `${RELEASE_VERSION}`,
5. push branch + tag,
6. publish GitHub Release,
7. bump `pom.xml` to `${NEXT_DEVELOPMENT_VERSION}` and commit.

---

## 6) Reusable release checklist

- [ ] README reflects active runtime/build behavior.
- [ ] Docs index includes all current deep-dive docs.
- [ ] `config/env-settings.properties` behavior documented.
- [ ] SCM override/proxy behavior documented.
- [ ] `mvn test` green.
- [ ] `mvn -B verify --file pom.xml` green.
- [ ] Native builds green (where applicable).
- [ ] Release notes include known limitations/warnings.

---

## 7) Post-release verification

After publishing any release:

1. download attached artifacts from GitHub Release,
2. smoke-test JAR and native binaries,
3. verify startup logs + dependency download/build flows,
4. verify release notes and docs links.

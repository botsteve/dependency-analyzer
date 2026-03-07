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
   - `GRAALVM_HOME=/path/to/graalvm mvn -Pnative -DskipTests package`
   - `GRAALVM_HOME=/path/to/graalvm mvn -Pgluonfx-native -DskipTests -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build`
4. Release notes draft is ready.

---

## 2) Version inputs

Current project versioning pattern is snapshot-based (`x.y.z-SNAPSHOT`).

For a release, define:

- `RELEASE_VERSION` (for example `2.0.0`)
- `NEXT_DEVELOPMENT_VERSION` (for example `2.0.1-SNAPSHOT` or `2.1.0-SNAPSHOT`)

---

## 3) Maven release plugin path (recommended)

`pom.xml` configures `maven-release-plugin` with tag name format based on project version.

### Generic command template

```bash
mvn -B release:clean
mvn -B release:prepare \
  -DreleaseVersion=${RELEASE_VERSION} \
  -DdevelopmentVersion=${NEXT_DEVELOPMENT_VERSION}
mvn -B release:perform
```

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
- Graal native binary (host dependent): `target/dependency-analyzer`
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

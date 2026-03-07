# Release Process for 2.0.0

This document describes a practical, reproducible release flow for `2.0.0` using this repository’s current Maven + GitHub Actions setup.

## 1) Preconditions

Before cutting the release:

1. Working tree is clean.
2. `main` contains all intended release changes.
3. Local build validations pass:
   - `mvn test`
   - `mvn -B verify --file pom.xml`
   - `GRAALVM_HOME=/path/to/graalvm mvn -Pnative -DskipTests package`
   - `GRAALVM_HOME=/path/to/graalvm mvn -Pgluonfx-native -DskipTests -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build`
4. Release notes draft is prepared.

## 2) Versioning strategy

Current project version pattern is snapshot-based (`x.y.z-SNAPSHOT`).

For `2.0.0`:
- release version: `2.0.0`
- next development version: typically `2.0.1-SNAPSHOT` (or `2.1.0-SNAPSHOT` if starting next minor line)

## 3) Maven release plugin path (recommended)

`pom.xml` already configures `maven-release-plugin` with:
- `tagNameFormat`: `@{project.version}`

Typical commands:

```bash
mvn -B release:clean
mvn -B release:prepare -DreleaseVersion=2.0.0 -DdevelopmentVersion=2.0.1-SNAPSHOT
mvn -B release:perform
```

What this does:
1. updates version from snapshot to release,
2. creates release commit,
3. creates git tag `2.0.0`,
4. bumps to next snapshot,
5. creates post-release commit.

## 4) GitHub release and workflow automation

The workflow `.github/workflows/maven.yml` triggers on `release.published` and uploads release artifacts.

Release artifacts expected:
- shaded JAR: `target/dependency-analyzer.jar`
- Graal native macOS binary: `target/dependency-analyzer`
- Gluon native macOS binary: `target/gluonfx/aarch64-darwin/dependency-analyzer`

Practical flow:
1. Push tag/commits produced by release plugin.
2. Create GitHub Release for tag `2.0.0` (or let existing automation consume published release event).
3. Confirm workflow run success and artifact attachment.

## 5) Manual release fallback (if not using release plugin)

If you prefer explicit manual control:

1. Change `pom.xml` version to `2.0.0`.
2. Run full validation commands.
3. Commit: `release: 2.0.0`.
4. Tag: `git tag 2.0.0`.
5. Push branch + tag.
6. Publish GitHub Release for `2.0.0`.
7. Bump `pom.xml` to `2.0.1-SNAPSHOT` and commit.

## 6) 2.0.0 release checklist

- [ ] README reflects native paths (`native` + `gluonfx-native`).
- [ ] Docs index includes all architecture/runtime deep dives.
- [ ] `config/env-settings.properties` migration behavior documented.
- [ ] SCM override behavior documented.
- [ ] All tests green.
- [ ] Native builds green.
- [ ] Release notes include known warnings/limitations (if any).

## 7) Post-release verification

After publishing `2.0.0`:

1. Download attached artifacts from GitHub Release.
2. Smoke-test JAR and native binaries.
3. Confirm startup logs and dependency download/build flows.
4. Verify docs links and release notes formatting.

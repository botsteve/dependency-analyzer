# 🎉 Release Notes - v2.0.0

Welcome to **v2.0.0**! This release is a major step forward for runtime stability, native-image support, and release-readiness documentation. 🚀

---

## 🌟 At a glance (from `1.0.0` → `2.0.0`)

- **7 commits** in the release range
- **98 files changed**
- **8,621 insertions / 1,815 deletions**
- Major codebase evolution from legacy namespace to `io.botsteve.dependencyanalyzer`

---

## ✨ What's new

- 🆕 Added **`JdkDownloadTask`** for managed JDK bootstrap (`JAVA8_HOME`, `JAVA11_HOME`, `JAVA17_HOME`, `JAVA21_HOME`).
- 🧭 Added **`NativeEntryPoint`** for native startup path support.
- 📦 Expanded native metadata (JNI/reflection/resources) for improved runtime compatibility.
- 📚 Added deep-dive docs series (`docs/01` through `docs/07`) plus this release note.
- 🧪 Added regression coverage for:
  - downloader status and retry behavior,
  - JDK download resolution,
  - proxy/no_proxy behavior,
  - UI task failure messaging.

---

## 🛡️ Reliability and UX improvements

- 🔧 Native-safe download flow hardened with stronger git CLI fallback behavior.
- ⏱️ Git command timeout handling improved to avoid hang scenarios.
- 🌐 Proxy handling expanded for `HTTPS_PROXY`/`HTTP_PROXY` and `NO_PROXY` matching.
- 🧾 Failure reporting improved with clearer operational status summaries.
- 🎛️ Task/progress lifecycle behavior improved for better user feedback during JDK bootstrap.

---

## ⚠️ Migration notes

- Settings now live in: `config/env-settings.properties`
  - legacy root settings can be migrated automatically.
- `LoginViewer` was removed.

---

## ✅ Validation performed

- `mvn test` ✅ (76 tests, 0 failures)
- `mvn clean package -DskipTests` ✅
- `mvn -B release:prepare -DreleaseVersion=2.0.0 -DdevelopmentVersion=2.0.1-SNAPSHOT` ✅

---

## 📌 Full change log from tag `1.0.0` to tag `2.0.0`

All commits in `git log 1.0.0..2.0.0` (oldest → newest):

1. `64f41d1` — chore: configure SCM for maven release (2026-02-16)
2. `786a63d` — chore: configure release plugin tag format (2026-02-16)
3. `65c3c18` — chore: prepare for next development iteration 1.0.1-SNAPSHOT (2026-02-16)
4. `4067286` — chore: rename project to dependency-viewer (2026-02-16)
5. `68bf363` — feat: migrate to dependency-analyzer and harden dependency workflows (2026-03-07)
6. `eb77404` — feat: harden native workflows and expand project docs (2026-03-07)
7. `4de9461` — [maven-release-plugin] prepare release 2.0.0 (2026-03-07)

For the exact file-level delta, run:

```bash
git diff --stat 1.0.0..2.0.0
```

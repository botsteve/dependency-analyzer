# 🎉 Release Notes - v2.0.1

This release focuses on reliability improvements across dependency parsing, license aggregation, SCM enrichment overrides, and task/progress behavior.

---

## 🌟 At a glance (`2.0.0` → `2.0.1`)

- **6 commits** in `git log 2.0.0..2.0.1`
- **52 files changed**
- **2,114 insertions / 693 deletions**

---

## ✨ Highlights

- ✅ Stabilized Maven dependency tree parsing by consuming deterministic JSON output files instead of noisy console scraping.
- ✅ Reworked aggregated license generation:
  - one report per selected 3rd-party dependency,
  - inclusion of valid 4th-party dependencies,
  - improved dedup/reference behavior for repeated license text,
  - dedicated output location and clearer generation summaries.
- ✅ Improved license/notice extraction quality:
  - ignore generated notice report files during scans,
  - include `NOTICE*` files (for example `NOTICE_Modifications`),
  - better copyright-holder extraction.
- ✅ Hardened SCM resolution and overrides:
  - normalized malformed `.git/...` URLs,
  - applied overrides in a final pass after enrichment,
  - added explicit override logging for troubleshooting.
- ✅ Fixed UI task behavior regressions for JDK download and progress indicators, including popup-triggered download flow.
- ✅ Improved startup diagnostics fallbacks for early logging/file-write failures.

---

## 🧪 Validation

- `mvn test` ✅
- `mvn clean package` ✅
- `mvn -B verify --file pom.xml` ✅

---

## 📌 Full change log from tag `2.0.0` to tag `2.0.1`

All commits in `git log 2.0.0..2.0.1` (oldest → newest):

1. `3c295ee` — [maven-release-plugin] prepare for next development iteration (2026-03-07)
2. `156b2a4` — chore(docs): update docs (2026-03-07)
3. `99fdb87` — ci: make native release artifacts best-effort (2026-03-07)
4. `e0b508a` — refactor: normalize local variable style and simplify JavaFX types (2026-03-07)
5. `0a4ca99` — fix: stabilize license generation and SCM enrichment flows (2026-03-08)
6. `f3cf7c7` — [maven-release-plugin] prepare release 2.0.1 (2026-03-08)

For exact file-level delta:

```bash
git diff --stat 2.0.0..2.0.1
```

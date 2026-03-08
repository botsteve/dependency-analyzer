# AGENTS.md

Operational guide for coding agents in this repository.
Use as the source of truth for commands, style, and validation.

## Repository profile
- Project: `dependency-analyzer`
- Language/runtime: Java 21
- Build: Maven
- UI: JavaFX
- Logging: SLF4J + Logback
- Tests: JUnit 5 + Mockito
- Packaging: shaded runnable JAR (`target/dependency-analyzer.jar`)
- Optional native build: GluonFX profile (`gluonfx-native`)

## Build / test / run commands
Run all commands from repository root.

### Core build
- Compile: `mvn compile`
- Clean compile: `mvn clean compile`
- Package shaded JAR: `mvn clean package`
- CI-style verify: `mvn -B verify --file pom.xml`

### Test commands (important)
- Full test suite: `mvn test`
- Single test class: `mvn -Dtest=UtilsTest test`
- Single test method: `mvn -Dtest=UtilsTest#testCollectLatestVersions test`
- Multiple classes:
  `mvn -Dtest=TableViewComponentFilterTest,ColumnsComponentOutputPopupTest,DependencyAnalyzerTest test`

### Run app
- Packaged JAR: `java -jar target/dependency-analyzer.jar`
- Maven run: `mvn javafx:run`

### Native build (Gluon)
```bash
GRAALVM_HOME=/path/to/graalvm \
mvn -Pgluonfx-native -DskipTests -Dgluonfx.target=host -Dgluonfx.attachList=none gluonfx:build
```
Expected macOS output:
- `target/gluonfx/aarch64-darwin/dependency-analyzer`

## Release workflow commands
### Standard release path
```bash
mvn -B release:clean
mvn -B release:prepare -DreleaseVersion=<x.y.z> -DdevelopmentVersion=<next>-SNAPSHOT
mvn -B release:perform
```

### Verbose troubleshooting mode
```bash
mvn -X -B release:clean release:prepare \
  -DdryRun=true \
  -DpushChanges=false \
  -DreleaseVersion=<x.y.z> \
  -DdevelopmentVersion=<next>-SNAPSHOT
```
`release:prepare` requires a clean working tree.

## Lint / formatting reality
- No dedicated lint plugin (no Spotless/Checkstyle/PMD/ErrorProne).
- Primary quality gates are Maven tests/build.
- Keep formatting aligned with adjacent code.
- Avoid unrelated formatting-only rewrites.

## Cursor / Copilot rule status
Checked paths:
- `.cursorrules`
- `.cursor/rules/`
- `.github/copilot-instructions.md`

Current status: **none found**.
If added later, treat them as mandatory and merge into this file.

## Code style guidelines (repo-derived)
### Imports
- Keep static imports grouped separately from normal imports.
- Prefer explicit imports in touched files.
- Do not mass-normalize import style in unrelated files.

### Formatting and structure
- Use same-line braces for class/method declarations.
- Prefer early returns for guard clauses.
- Keep methods focused; extract helpers for repeated logic.
- Follow local file style where legacy formatting exists.

### Naming
- Classes/interfaces: `PascalCase`
- Methods/fields/local vars: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- JavaFX worker classes typically end with `Task`.

### Types and collections
- Prefer concrete generics (`Set<DependencyNode>`, `Map<String, String>`).
- Avoid raw types.
- Prefer empty collections over returning `null`.
- Preserve deterministic ordering for user-visible output.

### Error handling
- Use `DependencyAnalyzerException` for domain/runtime failures.
- Include actionable context in exception messages.
- Do not swallow exceptions silently.
- In UI flows, convert operational failures to user alerts via `FxUtils` where appropriate.

### Logging
- Use SLF4J parameterized logging (`log.info("... {}", value)`).
- Avoid `System.out.println` for normal flow.
- Include context in warning/error logs (dependency, URL, phase, path).
- Keep high-volume diagnostics at debug level when possible.

### JavaFX + concurrency
- Run blocking/network/file work in `Task.call()`.
- Update UI state only on JavaFX thread (`Platform.runLater(...)`).
- Keep lifecycle handling explicit (`setOnSucceeded`, `setOnFailed`, `setOnCancelled`).

### Process and file operations
- Set explicit working directory/env for external tool execution.
- Validate paths/directories before clone/build/cleanup.
- Keep retries and failure messages explicit and traceable.

## Testing conventions
- Use JUnit 5 (`@Test`) and Mockito when mocking is needed.
- Add/update tests when behavior changes (services, tasks, normalization, report generation).
- Run focused tests first, then broader verification.

## Common pitfalls
- Violating JavaFX thread constraints.
- Mixing UI updates with background work.
- Bypassing SCM override final-pass behavior.
- Re-ingesting generated files during license scans.
- Assuming required JDK settings exist without validation.
- Editing native metadata without validating native build behavior.

## Definition of done
- Change scope matches request.
- Relevant tests run and pass.
- `mvn -B verify --file pom.xml` passes.
- Commands/docs are aligned across:
  - `README.md`
  - `docs/07-release-process.md`
  - `docs/release-notes/*`
  - `AGENTS.md`

## Documentation sync rule
When build/test/release behavior changes:
1. Update `AGENTS.md`.
2. Update `README.md` command examples.
3. Update `docs/07-release-process.md` release guidance.

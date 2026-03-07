# AGENTS.md

Operational contract for coding agents working in this repository.
Use this file as the source of truth for commands, coding style, and validation.

## Repository profile

- Project: `dependency-analyzer`
- Stack: Java 21, Maven, JavaFX
- Packaging: shaded runnable JAR via `maven-shade-plugin`
- Tests: JUnit 5 + Mockito
- Logging: SLF4J + Logback
- Native build: GraalVM `native-maven-plugin` under profile `native`

## Build / test / run commands (authoritative)

Run all commands from repository root.

### Core build commands

- Compile: `mvn compile`
- Clean compile: `mvn clean compile`
- Run all tests: `mvn test`
- Package shaded app JAR: `mvn clean package`
- CI-style verify: `mvn -B verify --file pom.xml`

### Single-test execution (important)

- Single test class: `mvn -Dtest=UtilsTest test`
- Single test method: `mvn -Dtest=UtilsTest#testCollectLatestVersions test`
- Multiple classes: `mvn -Dtest=TableViewComponentFilterTest,ColumnsComponentOutputPopupTest,DependencyAnalyzerTest test`

These forms are confirmed by existing repository docs and test usage.

### Run application

- JAR run: `java -jar target/dependency-analyzer.jar`
- Optional Maven run: `mvn javafx:run`
- Trust JAR naming from `pom.xml`: `<finalName>${project.artifactId}</finalName>`

### Native image build

- `GRAALVM_HOME=/path/to/graalvm mvn clean package -Pnative -DskipTests`

Prerequisites:

- GraalVM (JDK 21+) with `native-image` installed
- `GRAALVM_HOME` (or `JAVA_HOME`) pointing to that GraalVM
- Keep native metadata in sync:
  - `src/main/resources/META-INF/native-image/reflect-config.json`
  - `src/main/resources/META-INF/native-image/resource-config.json`

## Lint / formatting reality

- No dedicated lint plugin is configured (no Spotless/Checkstyle/PMD/ErrorProne).
- Primary quality gates are `mvn test` and `mvn clean package`.
- Keep formatting aligned with neighboring code; avoid broad style rewrites.

## Environment assumptions

- Required system vars for normal development:
  - `JAVA_HOME` (JDK 21+)
  - `MAVEN_HOME`
- Runtime build matrix envs configured through app settings:
  - `JAVA8_HOME`, `JAVA11_HOME`, `JAVA17_HOME`, `JAVA21_HOME`
- Proxy support:
  - `http_proxy` / `HTTP_PROXY`

## Cursor / Copilot policy files

Checked locations:

- `.cursorrules`
- `.cursor/rules/`
- `.github/copilot-instructions.md`

Current status in this repo: **none found**.
If any of these files are added, treat them as mandatory and merge their rules into this guide.

## Code style guidelines (derived from source)

### Imports

- Keep static imports grouped above normal imports.
- Prefer explicit imports in new/edited files.
- Existing wildcard imports appear in some utility files; do not mass-refactor them unless touching that code.
- Keep import blocks stable and avoid unrelated churn.

### Formatting

- Braces stay on same line for class/method declarations.
- Use early returns for guard clauses and null checks.
- Keep methods focused; extract helpers for repeated logic.
- Follow local file indentation/style when inconsistent sections already exist.

### Types and models

- Prefer concrete generics (`Set<DependencyNode>`, `Map<String, String>`).
- Avoid raw collections.
- Keep model/state classes simple; avoid mixing UI and infrastructure concerns.

### Lombok usage

- Lombok is actively used (`@Data`, `@Slf4j`).
- Continue adjacent-file Lombok patterns instead of replacing with boilerplate.
- Add explicit methods only when behavior differs from Lombok defaults.

### Naming conventions

- Classes/interfaces: PascalCase
- Methods/fields: camelCase
- Constants: UPPER_SNAKE_CASE
- JavaFX background workers: class names end in `Task`
- Tests: behavior-oriented method names (`test...` pattern currently common)

### Error handling

- Use `DependencyAnalyzerException` for domain/runtime failures.
- Include clear context in exception messages (what failed + where).
- Never swallow exceptions silently.
- In JavaFX workflows, convert operational failures to user-facing alerts via `FxUtils` where appropriate.

### Logging

- Use SLF4J parameterized logging (`log.info("... {}", value)`).
- Do not use `System.out.println`.
- Log actionable context (repo, URL, operation ID, etc.) for failures.
- Keep noisy logs at debug/trace levels.

### JavaFX + concurrency rules

- Perform blocking/network/file operations in `Task.call()`.
- Touch UI state on JavaFX thread (`Platform.runLater(...)`) only.
- Keep task lifecycle handling explicit (`succeeded`, `failed`, status updates).

### Null-safety and collections

- Guard external/boundary inputs for null and blank values.
- Prefer empty collections over returning null.
- Preserve deterministic ordering where output is user-visible.

### Process/file operations

- Use `ProcessBuilder` with explicit working directory/env when invoking Maven/Gradle.
- Validate directories before clone/build operations.
- For destructive operations (cleanup), keep retries and meaningful failure messages.

### Testing conventions

- Use JUnit 5 (`@Test`) + Mockito where mocking is required.
- Keep tests deterministic and fast; prefer unit-level coverage for services/utils/tasks.
- When changing behavior, run focused tests first, then broader suite.

## Common pitfalls to avoid

- Violating JavaFX thread rules.
- Regressing dependency-tree filtering or selection propagation.
- Assuming environment variables exist without validation.
- Changing native-image-sensitive code/resources without metadata updates.
- Performing unrelated formatting rewrites in functional PRs.

## Definition of done for agent changes

- Updated code/docs compile and are internally consistent.
- Relevant tests executed (at least targeted tests for touched behavior).
- No style drift beyond the touched scope.
- Error handling and logging remain meaningful.
- Packaging path remains valid (`mvn clean package` for app JAR path correctness).

## Documentation sync rule

When commands, packaging, profiles, or test strategy change:

1. Update this `AGENTS.md`.
2. Update `README.md` sections that mention build/test/run/verification.
3. Keep command examples consistent between both files.

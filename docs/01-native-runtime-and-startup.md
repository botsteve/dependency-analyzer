# Native Runtime and Startup Flow

This document explains how the application boots in both JVM and native modes, how logging is initialized, and what startup probes are used to diagnose early-stage failures.

## 1) Entry points

### JVM entry point
- `io.botsteve.dependencyanalyzer.Launcher`
- Starts JavaFX using the standard JAR path.

### Native entry point
- `io.botsteve.dependencyanalyzer.NativeEntryPoint`
- Used by both:
  - `native` profile (`org.graalvm.buildtools:native-maven-plugin`)
  - `gluonfx-native` profile (`com.gluonhq:gluonfx-maven-plugin`)

Native mode performs extra startup hardening before launching JavaFX:
1. Forces an SLF4J provider (`slf4j-simple` first, Logback fallback).
2. Initializes `dependency.analyzer.base.dir` through `LogUtils.initializeBaseDirProperty(...)`.
3. Registers default uncaught exception handler to surface thread-level startup crashes.
4. Emits startup probes to stderr and `/tmp/dependency-analyzer-startup-trace.log`.

## 2) Base directory and log path

`io.botsteve.dependencyanalyzer.utils.LogUtils` controls runtime file layout:
- Base dir system property: `dependency.analyzer.base.dir`
- Log dir: `<baseDir>/logs`
- Default log file: `<baseDir>/logs/dependency-analyzer.log`

Behavior:
- If base dir is already set, it is respected.
- For JAR execution, base dir is derived from the JAR location.
- For dev/source launches, falls back to `user.dir`.
- Log directory is created eagerly to avoid “silent no-log” startup states.

## 3) JavaFX startup lifecycle

In native mode the execution chain is:
1. `NativeEntryPoint.main(...)`
2. `Application.launch(...)`
3. `NativeEntryPoint.init()`
4. `NativeEntryPoint.start(Stage)`
5. `MainAppView.start(Stage)`

`NativeEntryPoint.start(...)` ensures configuration files are ready before UI display:
- `Utils.createSettingsFile()`
- `ScmRepositories.initializeOverridesFile()`

## 4) Why startup probes exist

Native JavaFX issues can fail before the regular logger is fully active. Startup probes provide:
- a guaranteed signal on stderr,
- a deterministic file trace (`/tmp/dependency-analyzer-startup-trace.log`),
- visibility for failures occurring before the normal app stage appears.

## 5) Operational checks

Useful checks during native troubleshooting:
- Confirm log file location from startup probe line: `Log file: ...`
- Confirm JavaFX stage lifecycle probe lines:
  - `NativeEntryPoint.start entered`
  - `MainAppView.start entered`
  - `Main JavaFX stage onShown fired`

If probes appear but application logs do not, prioritize SLF4J provider/resource checks in native metadata.

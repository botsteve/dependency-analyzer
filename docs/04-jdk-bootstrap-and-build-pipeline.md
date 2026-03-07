# JDK Bootstrap and Build Pipeline

This document explains how required JDKs are downloaded and how downloaded repositories are built with version-appropriate toolchains.

## 1) JDK bootstrap

Primary class: `JdkDownloadTask`

Responsibilities:
- resolve requested JDK settings (`JAVA8_HOME`, `JAVA11_HOME`, `JAVA17_HOME`, `JAVA21_HOME`),
- download archives from Adoptium API,
- extract safely (zip/tar with zip-slip protection),
- detect final `JAVA_HOME` directory,
- persist results into `config/env-settings.properties`.

Progress model:
- weighted phases (`DOWNLOAD_PHASE_WEIGHT`, `EXTRACT_PHASE_WEIGHT`),
- normalized progress bar updates,
- user-facing status messages via JavaFX `Task.updateMessage(...)`.

Architecture policy:
- JDK 8 uses x64 payload intentionally on ARM hosts when aarch64 is unavailable via the selected endpoint.

## 2) Build pipeline

Primary class: `BuildRepositoriesTask`

Responsibilities:
- enumerate downloaded repositories,
- detect build system (Maven/Gradle/Ant),
- select compatible JDK (resolver + fallback strategy),
- execute build commands with verbose flags,
- report structured success/failure summaries.

Common command style:
- Maven: `mvn clean install -X`
- Gradle: wrapper build with `--info`
- Ant: verbose execution

## 3) Toolchains and JDK selection

Primary support classes:
- `ToolchainsGenerator`
- `JavaVersionResolver`

The app can generate `toolchains.xml` for Maven and choose JDK versions based on project metadata/wrapper version.

## 4) UI task gating

`ButtonsComponent` enforces mutual exclusion for long-running tasks:
- load dependencies,
- download repositories,
- build repositories,
- download JDKs.

This avoids conflicting file/network/process operations from overlapping.

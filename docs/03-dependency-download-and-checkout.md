# Dependency Download and Checkout Pipeline

This document details how source repositories are downloaded for selected dependencies and how version tags are resolved.

## 1) Primary classes

- `ButtonsComponent` (task orchestration + UI state)
- `DependencyDownloaderTask` (clone/fetch/checkout execution)
- `CheckoutTagsTask` (JGit-based tag logic for JVM path)
- `ProxyUtil` (runtime proxy configuration)
- `OperationStatus` (structured success/failure formatting)

## 2) Request normalization

`DependencyDownloaderTask` builds a list of `DownloadRequest` records from selected table entries:
- SCM URL
- dependency version
- target directory
- fallback repo name

The downloader resolves a deterministic repo key (`targetDir/repoName`) to map checkout status back to UI rows.

## 3) Mirror and retry behavior

SCM URL can contain mirror candidates (`url1|url2|url3`).

For each candidate:
- retries up to `MAX_RETRIES_PER_URL`,
- exponential-ish backoff (`RETRY_BACKOFF_MS`),
- failure classification by message pattern:
  - `AUTH_FAILURE`
  - `DNS_FAILURE`
  - `TIMEOUT`
  - `REDIRECT_BLOCKED`
  - `NETWORK_FAILURE`

Checkout-specific failures additionally include:
- `TAG_MISS`
- `CHECKOUT_FAILURE`
- `NATIVE_RUNTIME` (native-image/JGit runtime metadata issues)

## 4) JVM vs native behavior

### JVM path
- Uses JGit for clone/fetch and checkout/tag operations.

### Native path (GluonFX)
- Uses system `git` CLI for clone/fetch and checkout/tag operations.
- This avoids native-image reflection gaps in JGit internals (for example enum/config metadata lookups).

Native tag flow:
1. `git for-each-ref --sort=-creatordate --format=%(refname:short) refs/tags`
2. apply same version-matching logic (`CheckoutTagsTask.versionsMatch(...)`)
3. fallback `git fetch --tags --prune` when no matches found
4. `git checkout --force --detach refs/tags/<selectedTag>`

## 5) Failure visibility

`DependencyDownloaderTask` now surfaces details in UI summary:
- code + trimmed reason text (`message=...`)
- up to first 10 failed repos in sorted order

This prevents opaque “all failed” errors and makes support faster.

## 6) Proxy behavior

Before network operations, downloader calls `ProxyUtil.configureProxyIfEnvAvailable()`.

Supported env lookup order:
1. `https_proxy` / `HTTPS_PROXY`
2. `http_proxy` / `HTTP_PROXY`

Bypass hosts via `no_proxy` / `NO_PROXY` (or `noproxy`).

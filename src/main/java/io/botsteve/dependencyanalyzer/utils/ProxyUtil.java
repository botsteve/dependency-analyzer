package io.botsteve.dependencyanalyzer.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyUtil {

  private static final Logger log = LoggerFactory.getLogger(ProxyUtil.class);

  /**
   * Builds a user-facing download failure message that also hints proxy misconfiguration.
   */
  public static String getProxyExceptionMessage(Map.Entry<String, String> versionScm, Throwable e) {
    return "Error downloading " + getRepoNameFromUrl(versionScm != null ? versionScm.getKey() : "") + ": " +
           e.getMessage() + "\n" +
           "Source code url might be incorrect or if you are behind a proxy, check proxy env settings: HTTP_PROXY/http_proxy";
  }

  /**
   * Resolves a repository display name from SCM URL.
   */
  public static String getRepoNameFromUrl(String scmUrl) {
    return ScmUrlUtils.resolveRepoName(scmUrl, "");
  }

  /**
   * Configures JVM proxy selector when proxy environment variables are available.
   *
   * @throws URISyntaxException when proxy URI from environment variables is malformed
   */
  public static void configureProxyIfEnvAvailable() throws URISyntaxException {
    Optional<URI> proxyUriOptional = resolveProxyUriFromEnv();
    if (proxyUriOptional.isPresent()) {
      URI proxyUri = proxyUriOptional.get();
      String host = proxyUri.getHost();
      int proxyPort = proxyUri.getPort();
      log.info("Setting proxy to {}:{}", host, proxyPort == -1 ? 80 : proxyPort);
      configureProxySelector(host, proxyPort, resolveNoProxyHostsFromEnv());
    }
  }

  /**
   * Resolves proxy URI from standard environment variable names.
   *
   * @return optional proxy URI
   * @throws URISyntaxException when proxy variable value is malformed
   */
  public static Optional<URI> resolveProxyUriFromEnv() throws URISyntaxException {
    String proxy = firstNonBlankEnv("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY");
    if (proxy == null) {
      return Optional.empty();
    }
    String normalized = proxy.trim();
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
      normalized = "http://" + normalized;
    }
    return Optional.of(new URI(normalized));
  }

  private static String firstNonBlankEnv(String... keys) {
    for (String key : keys) {
      String value = System.getenv(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /**
   * Configures a global HTTP proxy selector without bypass rules.
   */
  public static void configureProxySelector(String host, int proxyPort) {
    configureProxySelector(host, proxyPort, List.of());
  }

  /**
   * Resolves no-proxy host patterns from environment variables.
   */
  public static List<String> resolveNoProxyHostsFromEnv() {
    String noProxy = firstNonBlankEnv("no_proxy", "NO_PROXY", "noproxy", "NOPROXY");
    if (noProxy == null) {
      return List.of();
    }
    List<String> hosts = new ArrayList<>();
    for (String token : noProxy.split(",")) {
      String candidate = token == null ? "" : token.trim();
      if (!candidate.isBlank()) {
        hosts.add(candidate);
      }
    }
    return hosts;
  }

  /**
   * Configures a global HTTP proxy selector with optional no-proxy bypass hosts.
   */
  public static void configureProxySelector(String host, int proxyPort, List<String> noProxyHosts) {
    ProxySelector.setDefault(new ProxySelector() {
      final Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, proxyPort == -1 ? 80 : proxyPort));
      final List<String> bypassHosts = noProxyHosts == null ? List.of() : List.copyOf(noProxyHosts);

      @Override
      public List<Proxy> select(URI uri) {
        String uriHost = uri == null ? "" : uri.getHost();
        if (shouldBypassProxy(uriHost, bypassHosts)) {
          return List.of(Proxy.NO_PROXY);
        }
        return List.of(proxy); // Use the specified proxy for all connections
      }

      @Override
      public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        log.error("Failed to connect to {}", uri, ioe);
        throw new RuntimeException(ioe);
      }
    });
  }

  /**
   * Checks whether a host should bypass proxy routing based on no-proxy rules.
   */
  static boolean shouldBypassProxy(String host, List<String> noProxyHosts) {
    if (host == null || host.isBlank() || noProxyHosts == null || noProxyHosts.isEmpty()) {
      return false;
    }

    String normalizedHost = normalizeHost(host);
    for (String rule : noProxyHosts) {
      String normalizedRule = normalizeHost(rule);
      if (normalizedRule.isBlank()) {
        continue;
      }
      if ("*".equals(normalizedRule)) {
        return true;
      }
      if (normalizedRule.startsWith("*.")) {
        String suffix = normalizedRule.substring(1);
        if (normalizedHost.endsWith(suffix)) {
          return true;
        }
      }
      if (normalizedRule.startsWith(".")) {
        String suffix = normalizedRule.substring(1);
        if (normalizedHost.equals(suffix) || normalizedHost.endsWith('.' + suffix)) {
          return true;
        }
      }
      if (normalizedHost.equals(normalizedRule)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeHost(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
      try {
        URI uri = new URI(normalized);
        String uriHost = uri.getHost();
        normalized = uriHost == null ? normalized : uriHost;
      } catch (URISyntaxException e) {
        log.debug("Could not parse no_proxy host '{}'", value, e);
      }
    }
    return stripOptionalPort(normalized);
  }

  private static String stripOptionalPort(String host) {
    if (host == null || host.isBlank()) {
      return "";
    }

    String normalized = host.trim();
    if (normalized.startsWith("[")) {
      int closingBracket = normalized.indexOf(']');
      if (closingBracket > 0) {
        return normalized.substring(1, closingBracket).toLowerCase(Locale.ROOT);
      }
      return normalized.toLowerCase(Locale.ROOT);
    }

    int colonCount = 0;
    for (int i = 0; i < normalized.length(); i++) {
      if (normalized.charAt(i) == ':') {
        colonCount++;
      }
    }
    if (colonCount > 1) {
      return normalized.toLowerCase(Locale.ROOT);
    }

    int portSeparator = normalized.indexOf(':');
    if (portSeparator > -1 && portSeparator + 1 < normalized.length()) {
      String portCandidate = normalized.substring(portSeparator + 1);
      if (portCandidate.chars().allMatch(Character::isDigit)) {
        normalized = normalized.substring(0, portSeparator);
      }
    }
    return normalized.toLowerCase(Locale.ROOT);
  }
}

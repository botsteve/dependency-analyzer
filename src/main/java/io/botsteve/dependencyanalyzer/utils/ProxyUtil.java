package io.botsteve.dependencyanalyzer.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyUtil {

  private static final Logger log = LoggerFactory.getLogger(ProxyUtil.class);

  public static String getProxyExceptionMessage(Map.Entry<String, String> versionScm, Throwable e) {
    return "Error downloading " + getRepoNameFromUrl(versionScm != null ? versionScm.getKey() : "") + ": " +
           e.getMessage() + "\n" +
           "Source code url might be incorrect or if you are behind a proxy, check proxy env settings: HTTP_PROXY/http_proxy";
  }

  public static String getRepoNameFromUrl(String scmUrl) {
    return ScmUrlUtils.resolveRepoName(scmUrl, "");
  }

  public static void configureProxyIfEnvAvailable() throws URISyntaxException {
    String httpProxy = System.getenv("http_proxy");
    if (httpProxy != null) {
      // Ensure the URI has a scheme, if not, prepend "http://"
      if (!httpProxy.startsWith("http://") && !httpProxy.startsWith("https://")) {
        httpProxy = "http://" + httpProxy;
      }
      URI proxyUri = new URI(httpProxy);
      String host = proxyUri.getHost();
      int proxyPort = proxyUri.getPort();
      log.info("Setting proxy to {}:{}", host, proxyPort == -1 ? 80 : proxyPort);
      configureProxySelector(host, proxyPort);
    }
  }

  public static void configureProxySelector(String host, int proxyPort) {
    ProxySelector.setDefault(new ProxySelector() {
      final Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, proxyPort == -1 ? 80 : proxyPort));

      @Override
      public List<Proxy> select(URI uri) {
        return List.of(proxy); // Use the specified proxy for all connections
      }

      @Override
      public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        log.error("Failed to connect to {}", uri, ioe);
        throw new RuntimeException(ioe);
      }
    });
  }
}

package io.botsteve.dependencyanalyzer.service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.utils.ScmUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.fixNonResolvableScmRepositorise;

/**
 * Enriches dependency nodes with SCM (VCS) URLs by querying local caches
 * and remote repositories (Maven Central). Works for both Maven and Gradle projects.
 */
public class ScmEnrichmentService {

  private static final Logger log = LoggerFactory.getLogger(ScmEnrichmentService.class);

  private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";
  private static final int DEFAULT_MAX_WORKERS = 6;
  private static final int MAX_RETRIES = 2;

  private static final ConcurrentHashMap<String, ScmResolution> RESOLVED_CACHE = new ConcurrentHashMap<>();

  /**
   * Fetches SCM URLs for all dependencies that don't already have one.
   * Uses parallel processing and caching for performance.
   */
  public static void fetchScmUrls(Set<DependencyNode> dependencies) {
    Set<DependencyNode> allNodes = new HashSet<>();
    collectAllNodes(dependencies, allNodes);

    // Filter nodes that actually need a fetch
    Set<DependencyNode> nodesToFetch = allNodes.stream()
        .filter(ScmEnrichmentService::shouldFetch)
        .collect(Collectors.toSet());

    if (nodesToFetch.isEmpty()) {
      return;
    }

    List<DependencyNode> workItems = new ArrayList<>(nodesToFetch);
    int workers = Math.max(1, Math.min(DEFAULT_MAX_WORKERS, workItems.size()));
    log.info("Enriching {} unique dependencies using {} workers...", workItems.size(), workers);

    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (DependencyNode node : workItems) {
        futures.add(executor.submit((Callable<Void>) () -> {
          enrichNode(node);
          return null;
        }));
      }

      for (Future<Void> future : futures) {
        try {
          future.get();
        } catch (Exception e) {
          log.debug("SCM enrichment worker failed: {}", e.getMessage());
        }
      }
    } finally {
      executor.shutdown();
      try {
        executor.awaitTermination(2, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void enrichNode(DependencyNode node) {
    String key = String.format("%s:%s:%s", node.getGroupId(), node.getArtifactId(), node.getVersion());
    ScmResolution resolution = RESOLVED_CACHE.computeIfAbsent(key,
        ignored -> resolveScmUrl(node.getGroupId(), node.getArtifactId(), node.getVersion(), 0));

    if (resolution.outcome() == PomFetchOutcome.SUCCESS && !resolution.scmUrl().isBlank()) {
      String normalizedScmUrl = ScmUrlUtils.canonicalize(resolution.scmUrl());
      node.setScmUrl(fixNonResolvableScmRepositorise(normalizedScmUrl, node.getGroupId(), node.getArtifactId()));
      return;
    }

    node.setScmUrl("SCM URL not found");
  }

  private static void collectAllNodes(Set<DependencyNode> nodes, Set<DependencyNode> accumulator) {
    if (nodes == null) return;
    for (DependencyNode node : nodes) {
      if (accumulator.add(node)) {
        collectAllNodes(node.getChildren() == null ? null : new HashSet<>(node.getChildren()), accumulator);
      }
    }
  }

  private static boolean shouldFetch(DependencyNode node) {
    String current = node.getScmUrl();
    return current == null || current.isEmpty() || "SCM URL not found".equals(current);
  }

  /**
   * Resolves SCM URL by checking the local .m2 cache or Maven Central, 
   * following parents recursively if needed.
   */
  private static ScmResolution resolveScmUrl(String groupId, String artifactId, String version, int depth) {
    if (depth > 3 || groupId == null || artifactId == null || version == null) {
      return new ScmResolution("", PomFetchOutcome.PARENT_CHAIN_EXHAUSTED);
    }

    PomFetchResult fetchResult = fetchPom(groupId, artifactId, version);
    if (fetchResult.outcome() != PomFetchOutcome.SUCCESS || fetchResult.document() == null) {
      return new ScmResolution("", fetchResult.outcome());
    }

    Document doc = fetchResult.document();

    // 1. Try <scm> tags in current POM
    String scmInfo = extractScmFromDoc(doc);
    if (scmInfo != null) {
      return new ScmResolution(scmInfo, PomFetchOutcome.SUCCESS);
    }

    // 2. Try top-level <url> (Homepage) as fallback
    NodeList urlNodes = doc.getElementsByTagName("url");
    if (urlNodes.getLength() > 0) {
      String homepage = urlNodes.item(0).getTextContent().trim();
      if (!homepage.isEmpty() && !homepage.contains("${")) {
        // Many homepages are just GitHub repos
        if (homepage.contains("github.com") || homepage.contains("gitlab.com")) {
            return new ScmResolution(homepage, PomFetchOutcome.SUCCESS);
        }
      }
    }

    // 3. Follow Parent POM
    NodeList parentNodes = doc.getElementsByTagName("parent");
    if (parentNodes.getLength() > 0) {
      Element parent = (Element) parentNodes.item(0);
      String pGroup = getTagValue(parent, "groupId");
      String pArtifact = getTagValue(parent, "artifactId");
      String pVersion = getTagValue(parent, "version");
      
      if (pGroup != null && pArtifact != null && pVersion != null) {
        log.info("Following parent POM for enrichment: {}:{}:{} (level {})", pGroup, pArtifact, pVersion, depth + 1);
        return resolveScmUrl(pGroup, pArtifact, pVersion, depth + 1);
      }
    }

    return new ScmResolution("", PomFetchOutcome.NOT_FOUND);
  }

  private static String extractScmFromDoc(Document doc) {
    NodeList scmNodes = doc.getElementsByTagName("scm");
    if (scmNodes.getLength() > 0) {
      Element scmElement = (Element) scmNodes.item(0);

      // Order of preference: <url> -> <connection> -> <developerConnection>
      String[] tags = {"url", "connection", "developerConnection"};
      for (String tag : tags) {
        String val = getTagValue(scmElement, tag);
        if (val != null && !val.isEmpty() && !val.contains("${")) {
          return val;
        }
      }
    }
    return null;
  }

  private static String getTagValue(Element element, String tagName) {
    NodeList list = element.getElementsByTagName(tagName);
    if (list != null && list.getLength() > 0) {
        // Ensure we take the direct child tag value, not an inherited one deep inside
        return list.item(0).getTextContent().trim();
    }
    return null;
  }

  /**
   * Attempts to load POM from local .m2 repo or Maven Central.
   */
  private static PomFetchResult fetchPom(String groupId, String artifactId, String version) {
    try {
      // Try local .m2 repo first to save bandwidth/time
      String m2Repo = System.getProperty("user.home") + "/.m2/repository";
      Path localPath = Paths.get(m2Repo, groupId.replace('.', '/'), artifactId, version,
          String.format("%s-%s.pom", artifactId, version));
      
      if (Files.exists(localPath)) {
        try (InputStream is = Files.newInputStream(localPath)) {
          return new PomFetchResult(parseXml(is), PomFetchOutcome.SUCCESS);
        } catch (Exception e) {
          return new PomFetchResult(null, PomFetchOutcome.PARSE_FAILURE);
        }
      }

      // Fallback to Maven Central
      String groupPath = groupId.replace('.', '/');
      String pomUrl = String.format("%s/%s/%s/%s/%s-%s.pom",
          MAVEN_CENTRAL_BASE, groupPath, artifactId, version, artifactId, version);

      int attempt = 0;
      while (attempt <= MAX_RETRIES) {
        HttpURLConnection connection = (HttpURLConnection) URI.create(pomUrl).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);

        try {
          int responseCode = connection.getResponseCode();
          PomFetchOutcome responseOutcome = outcomeFromHttpCode(responseCode);
          if (responseOutcome == PomFetchOutcome.NOT_FOUND) {
            return new PomFetchResult(null, responseOutcome);
          }
          if (responseCode == 200) {
            try (InputStream is = connection.getInputStream()) {
              return new PomFetchResult(parseXml(is), PomFetchOutcome.SUCCESS);
            } catch (Exception e) {
              return new PomFetchResult(null, PomFetchOutcome.PARSE_FAILURE);
            }
          }
          if (shouldRetryResponse(responseCode, attempt)) {
            backoffSleep(attempt);
            attempt++;
            continue;
          }
          return new PomFetchResult(null, PomFetchOutcome.NETWORK_FAILURE);
        } catch (SocketTimeoutException e) {
          if (attempt >= MAX_RETRIES) {
            return new PomFetchResult(null, PomFetchOutcome.NETWORK_FAILURE);
          }
          backoffSleep(attempt);
          attempt++;
        } catch (Exception e) {
          if (attempt >= MAX_RETRIES) {
            return new PomFetchResult(null, PomFetchOutcome.NETWORK_FAILURE);
          }
          backoffSleep(attempt);
          attempt++;
        } finally {
          connection.disconnect();
        }
      }
    } catch (Exception e) {
      log.debug("Failed to fetch POM for {}:{}:{}: {}", groupId, artifactId, version, e.getMessage());
    }
    return new PomFetchResult(null, PomFetchOutcome.NOT_FOUND);
  }

  private static void backoffSleep(int attempt) {
    long delayMillis = attempt == 0 ? 300L : 900L;
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static PomFetchOutcome outcomeFromHttpCode(int responseCode) {
    if (responseCode == 200) {
      return PomFetchOutcome.SUCCESS;
    }
    if (responseCode == 404) {
      return PomFetchOutcome.NOT_FOUND;
    }
    if (responseCode >= 500) {
      return PomFetchOutcome.NETWORK_FAILURE;
    }
    return PomFetchOutcome.PARSE_FAILURE;
  }

  static boolean shouldRetryResponse(int responseCode, int attempt) {
    return responseCode >= 500 && attempt < MAX_RETRIES;
  }

  private static Document parseXml(InputStream is) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(is);
    doc.getDocumentElement().normalize();
    return doc;
  }

  /**
   * Canonicalizes SCM URL value into a normalized HTTP(S) form.
   */
  public static String convertSCM(String scmUrl) {
    return ScmUrlUtils.canonicalize(scmUrl);
  }

  public enum PomFetchOutcome {
    SUCCESS,
    NOT_FOUND,
    NETWORK_FAILURE,
    PARSE_FAILURE,
    PARENT_CHAIN_EXHAUSTED
  }

  public record ScmResolution(String scmUrl, PomFetchOutcome outcome) {
  }

  private record PomFetchResult(Document document, PomFetchOutcome outcome) {
  }
}

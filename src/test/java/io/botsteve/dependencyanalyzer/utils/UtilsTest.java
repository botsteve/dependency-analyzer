package io.botsteve.dependencyanalyzer.utils;

import io.botsteve.dependencyanalyzer.model.DependencyNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UtilsTest {

    @Test
    public void testCollectLatestVersions() {
      Set<DependencyNode> nodes = new HashSet<>();

      DependencyNode node1 = new DependencyNode();
        node1.setScmUrl("http://example.com/repo1");
        node1.setVersion("1.0.0");

      DependencyNode node2 = new DependencyNode();
        node2.setScmUrl("http://example.com/repo1");
        node2.setVersion("1.1.0");

      DependencyNode node3 = new DependencyNode();
        node3.setScmUrl("http://example.com/repo2");
        node3.setVersion("2.0.0");

        nodes.add(node1);
        nodes.add(node2);
        nodes.add(node3);

      Map<String, String> result = Utils.collectLatestVersions(nodes);

        assertEquals(2, result.size());
        assertEquals("1.1.0", result.get("http://example.com/repo1"));
        assertEquals("2.0.0", result.get("http://example.com/repo2"));
    }
}

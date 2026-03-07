package io.botsteve.dependencyanalyzer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DependencyNodeTest {

  @Test
  void shouldUseGroupArtifactVersionForEquality() {
    DependencyNode left = new DependencyNode("g", "a", "1.0.0");
    DependencyNode right = new DependencyNode("g", "a", "1.0.0");
    DependencyNode different = new DependencyNode("g", "a", "1.0.1");

    assertEquals(left, right);
    assertEquals(left.hashCode(), right.hashCode());
    assertNotEquals(left, different);
  }

  @Test
  void shouldSupportSelectionAndBuildProperties() {
    DependencyNode node = new DependencyNode("g", "a", "1.0.0");
    node.setSelected(true);
    node.setBuildWith("Java 17");
    node.setCheckoutTag("v1.0.0");

    assertTrue(node.isSelected());
    assertEquals("Java 17", node.getBuildWith());
    assertEquals("v1.0.0", node.getCheckoutTag());
  }
}

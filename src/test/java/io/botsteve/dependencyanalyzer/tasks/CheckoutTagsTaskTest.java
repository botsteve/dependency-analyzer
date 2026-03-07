package io.botsteve.dependencyanalyzer.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class CheckoutTagsTaskTest {

  @Test
  public void testShouldMatchSnakeYamlStyleTags() {
    assertTrue(CheckoutTagsTask.versionsMatch("2.4", "snakeyaml-2.4"));
    assertTrue(CheckoutTagsTask.versionsMatch("2.4", "refs/tags/snakeyaml-2.4"));
    assertTrue(CheckoutTagsTask.versionsMatch("2.4", "snakeyaml-2.4.1"));
    assertTrue(CheckoutTagsTask.versionsMatch("2.4", "snakeyaml-2.4.10"));
    assertFalse(CheckoutTagsTask.versionsMatch("2.4", "snakeyaml-2.40"));
    assertFalse(CheckoutTagsTask.versionsMatch("2.4.10", "snakeyaml-2.4"));
  }

  @Test
  public void testShouldNormalizeVersionTokenFromTagNames() {
    assertEquals("2.4", CheckoutTagsTask.normalizeVersion("snakeyaml-2.4"));
    assertEquals("1.33", CheckoutTagsTask.normalizeVersion("refs/tags/snakeyaml-1.33"));
  }

  @Test
  public void testShouldFetchTagsWhenNoMatchInitially() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/botsteve/dependencyanalyzer/tasks/CheckoutTagsTask.java"));
    assertTrue(source.contains("setTagOpt(TagOpt.FETCH_TAGS)"));
  }
}

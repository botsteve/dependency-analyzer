package io.botsteve.dependencyanalyzer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScmCanonicalizationTest {

  @Test
  void testScmUrlCanonicalization() {
    shouldCanonicalizeEquivalentGitUrlForms();
  }

  @Test
  void shouldCanonicalizeEquivalentGitUrlForms() {
    String expected = "https://github.com/helidon-io/helidon";
    assertEquals(expected, ScmUrlUtils.canonicalize("git://github.com/helidon-io/helidon.git"));
    assertEquals(expected, ScmUrlUtils.canonicalize("scm:git:https://github.com/helidon-io/helidon.git"));
    assertEquals(expected, ScmUrlUtils.canonicalize("git@github.com:helidon-io/helidon.git"));
    assertEquals(expected, ScmUrlUtils.canonicalize("https://github.com/helidon-io/helidon/"));
  }

  @Test
  void shouldNotLowercaseRepositoryPathSegments() {
    assertEquals("https://github.com/Oracle/MyRepo",
        ScmUrlUtils.canonicalize("https://GITHUB.COM/Oracle/MyRepo.git"));
  }

  @Test
  void shouldKeepMissingScmSentinelUntouched() {
    assertEquals("SCM URL not found", ScmUrlUtils.canonicalize("SCM URL not found"));
    assertEquals("SCM URL not found", ScmUrlUtils.canonicalize("  SCM URL not found  "));
  }

  @Test
  void shouldUseArtifactFallbackWhenScmUrlContainsOnlyHost() {
    assertEquals("snakeyaml", ScmUrlUtils.resolveRepoName("https://github.com", "snakeyaml"));
  }

  @Test
  void shouldStripPathAfterGitSuffixForMalformedScmUrls() {
    String expected = "https://github.com/javaee/jaxb-spec";
    assertEquals(expected,
        ScmUrlUtils.canonicalize("https://github.com/javaee/jaxb-spec.git/jaxb-api"));
    assertEquals(expected,
        ScmUrlUtils.canonicalize("scm:git:https://github.com/javaee/jaxb-spec.git/jaxb-api"));
  }
}

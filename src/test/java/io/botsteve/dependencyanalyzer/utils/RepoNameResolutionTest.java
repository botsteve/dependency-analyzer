package io.botsteve.dependencyanalyzer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RepoNameResolutionTest {

  @Test
  void testRepoNameResolution() {
    shouldResolveGitboxQueryRepoName();
  }

  @Test
  void shouldResolveGitboxQueryRepoName() {
    String url = "https://gitbox.apache.org/repos/asf?p=commons-text.git";
    assertEquals("commons-text", ScmUrlUtils.resolveRepoName(url, "fallback"));
  }

  @Test
  void shouldSanitizeFilesystemUnsafeCharacters() {
    assertEquals("abc_def_ghi_jkl", ScmUrlUtils.sanitizePathSegment("abc/def:ghi?jkl"));
  }

  @Test
  void shouldResolveRepoNameFromSourceBrowserUrl() {
    String url = "https://bitbucket.org/asomov/snakeyaml/src/master";
    assertEquals("snakeyaml", ScmUrlUtils.resolveRepoName(url, "fallback"));
  }

  @Test
  void shouldResolveRepoNameFromTreeUrl() {
    String url = "https://github.com/apache/commons-lang/tree/master";
    assertEquals("commons-lang", ScmUrlUtils.resolveRepoName(url, "fallback"));
  }

  @Test
  void shouldResolveRepoNameFromGitLabTreeUrl() {
    String url = "https://gitlab.com/group/subgroup/my-repo/-/tree/main";
    assertEquals("my-repo", ScmUrlUtils.resolveRepoName(url, "fallback"));
  }

  @Test
  void shouldResolveRepoNameFromMalformedGitSuffixPath() {
    String url = "https://github.com/javaee/jaxb-spec.git/jaxb-api";
    assertEquals("jaxb-spec", ScmUrlUtils.resolveRepoName(url, "fallback"));
  }
}

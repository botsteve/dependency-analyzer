package io.botsteve.dependencyanalyzer.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProxyUtilNoProxyTest {

  @Test
  void shouldBypassProxyForExactHost() {
    assertTrue(ProxyUtil.shouldBypassProxy("repo.mycorp.internal", List.of("repo.mycorp.internal")));
  }

  @Test
  void shouldBypassProxyForLeadingDotSuffixRule() {
    assertTrue(ProxyUtil.shouldBypassProxy("artifact.mycorp.internal", List.of(".mycorp.internal")));
  }

  @Test
  void shouldBypassProxyForWildcardSuffixRule() {
    assertTrue(ProxyUtil.shouldBypassProxy("artifact.mycorp.internal", List.of("*.mycorp.internal")));
  }

  @Test
  void shouldNotBypassProxyWhenHostDoesNotMatchRule() {
    assertFalse(ProxyUtil.shouldBypassProxy("repo.github.com", List.of(".mycorp.internal")));
  }
}

package io.botsteve.dependencyanalyzer.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ButtonsComponentTaskLifecycleTest {

  @Test
  void testTaskLifecycleIntegration() throws Exception {
    shouldPreferSuccessfulBuildStatusWhenMultipleNestedStatusesExist();
  }

  @Test
  void shouldPreferSuccessfulBuildStatusWhenMultipleNestedStatusesExist() throws Exception {
    Map<String, String> statusByRepo = new HashMap<>();
    statusByRepo.put("root/third", "FAILED:BUILD_FAILURE [op=BUILD-123,dur=10ms] x");
    statusByRepo.put("root/third/nested", "SUCCESS [op=BUILD-456,dur=20ms] Built with Java 17");

    Method resolveBestStatus = ButtonsComponent.class.getDeclaredMethod(
        "resolveBestStatus", Map.class, Set.class);
    resolveBestStatus.setAccessible(true);

    String resolved = (String) resolveBestStatus.invoke(null, statusByRepo, Set.of("root/third", "root/third/nested"));
    assertEquals("SUCCESS [op=BUILD-456,dur=20ms] Built with Java 17", resolved);
  }
}

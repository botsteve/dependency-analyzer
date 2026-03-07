package io.botsteve.dependencyanalyzer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScmEnrichmentServiceRetryTest {

  @Test
  void shouldClassifyPermanentNotFoundWithoutRetry() {
    assertEquals(ScmEnrichmentService.PomFetchOutcome.NOT_FOUND, ScmEnrichmentService.outcomeFromHttpCode(404));
    assertFalse(ScmEnrichmentService.shouldRetryResponse(404, 0));
  }

  @Test
  void shouldRetryTransientServerErrorsBoundedly() {
    assertTrue(ScmEnrichmentService.shouldRetryResponse(503, 0));
    assertFalse(ScmEnrichmentService.shouldRetryResponse(503, 2));
  }
}

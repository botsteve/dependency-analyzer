package io.botsteve.dependencyanalyzer.logging;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MemoryLogger {


  public static void logMemoryUsage() {
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    // Get the heap memory usage
    MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
    long heapUsed = heapMemoryUsage.getUsed();
    long heapCommitted = heapMemoryUsage.getCommitted();
    long heapMax = heapMemoryUsage.getMax();

    // Get the non-heap memory usage
    MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
    long nonHeapUsed = nonHeapMemoryUsage.getUsed();
    long nonHeapCommitted = nonHeapMemoryUsage.getCommitted();
    long nonHeapMax = nonHeapMemoryUsage.getMax();

    // Log the memory usage
    log.trace("Heap Memory: Used = {} MB, Committed = {} MB, Max = {} MB",
             toMB(heapUsed), toMB(heapCommitted), toMB(heapMax));
    log.trace("Non-Heap Memory: Used = {} MB, Committed = {} MB, Max = {} MB",
             toMB(nonHeapUsed), toMB(nonHeapCommitted), toMB(nonHeapMax));
  }

  private static long toMB(long bytes) {
    return bytes / (1024 * 1024);
  }
}

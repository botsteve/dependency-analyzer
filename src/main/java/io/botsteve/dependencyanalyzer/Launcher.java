package io.botsteve.dependencyanalyzer;

import io.botsteve.dependencyanalyzer.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * JVM-first bootstrap entry point.
 *
 * <p>This class initializes runtime base directory and startup diagnostics before delegating
 * to {@link DependencyAnalyzer}. It is used as the shaded JAR manifest main class.</p>
 */
public class Launcher {
    private static final Logger log = LoggerFactory.getLogger(Launcher.class);
    private static final Path STARTUP_TRACE_FILE = Path.of("/tmp", "dependency-analyzer-startup-trace.log");

    /**
     * Starts the application through the JVM launch path.
     *
     * @param args process arguments propagated to {@link DependencyAnalyzer#main(String[])}
     */
    public static void main(String[] args) {
        LogUtils.initializeBaseDirProperty(Launcher.class);
        System.setProperty("prism.order", "sw");
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            startupProbe("Uncaught exception on thread " + thread.getName() + ": " + throwable.getClass().getName() + " - " + throwable.getMessage());
            log.error("Uncaught exception on thread {}", thread.getName(), throwable);
        });
        startupProbe("Launcher.main entered");
        try {
            DependencyAnalyzer.main(args);
        } catch (Throwable t) {
            startupProbe("Launcher.main uncaught failure: " + t.getClass().getName() + ": " + t.getMessage());
            log.error("Uncaught launcher failure", t);
            throw t;
        }
    }

    /**
     * Writes an early startup marker to stderr and the startup trace file.
     *
     * @param message probe message
     */
    private static void startupProbe(String message) {
      String line = "[startup] " + message + System.lineSeparator();
        try {
            log.info("[startup] {}", message);
        } catch (Throwable loggingFailure) {
            System.err.print(line);
        }
        try {
            Path parent = STARTUP_TRACE_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(STARTUP_TRACE_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            try {
                log.warn("[startup] Failed writing startup trace file: {}", e.getMessage());
            } catch (Throwable ignored) {
                System.err.print(line);
                System.err.println("[startup] Failed writing startup trace file: " + e.getMessage());
            }
        }
    }
}

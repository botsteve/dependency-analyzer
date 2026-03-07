package io.botsteve.dependencyanalyzer;

import io.botsteve.dependencyanalyzer.utils.LogUtils;

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
            throwable.printStackTrace(System.err);
        });
        startupProbe("Launcher.main entered");
        try {
            DependencyAnalyzer.main(args);
        } catch (Throwable t) {
            System.err.println("[startup] Uncaught launcher failure: " + t.getMessage());
            startupProbe("Launcher.main uncaught failure: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
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
        System.err.print(line);
        try {
            Files.writeString(STARTUP_TRACE_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[startup] Failed writing startup trace file: " + e.getMessage());
        }
    }
}

package io.botsteve.dependencyanalyzer;


import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import io.botsteve.dependencyanalyzer.utils.LogUtils;
import io.botsteve.dependencyanalyzer.views.MainAppView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.initializeOverridesFile;
import static io.botsteve.dependencyanalyzer.utils.Utils.createSettingsFile;
public class DependencyAnalyzer extends Application {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzer.class);
    private static final Path STARTUP_TRACE_FILE = Path.of("/tmp", "dependency-analyzer-startup-trace.log");

    public DependencyAnalyzer() {
        startupProbe("DependencyAnalyzer constructor entered");
    }

    /**
     * JavaFX lifecycle hook executed before primary stage creation.
     */
    @Override
    public void init() {
        startupProbe("DependencyAnalyzer.init entered");
    }

    /**
     * Starts the main JavaFX application view and initializes runtime configuration files.
     *
     * @param primaryStage primary JavaFX stage
     */
    @Override
    public void start(Stage primaryStage) {
        startupProbe("DependencyAnalyzer.start entered");
        createSettingsFile();
        initializeOverridesFile();
        log.info("Starting main JavaFX view");
        new MainAppView().start(primaryStage);
        startupProbe("MainAppView.start returned to DependencyAnalyzer.start");
        Platform.setImplicitExit(true);

    }

    /**
     * Application entry point.
     *
     * @param args application arguments
     */
    public static void main(String[] args) {
        LogUtils.initializeBaseDirProperty(DependencyAnalyzer.class);
        launch(DependencyAnalyzer.class, args);
    }

    /**
     * JavaFX lifecycle shutdown hook.
     */
    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        // Add any other cleanup code here
        Platform.exit();
        System.exit(0);
    }

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

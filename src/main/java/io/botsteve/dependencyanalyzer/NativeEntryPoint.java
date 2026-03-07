package io.botsteve.dependencyanalyzer;

import io.botsteve.dependencyanalyzer.views.MainAppView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static io.botsteve.dependencyanalyzer.utils.LogUtils.getDefaultLogFilePath;
import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.initializeOverridesFile;
import static io.botsteve.dependencyanalyzer.utils.LogUtils.initializeBaseDirProperty;
import static io.botsteve.dependencyanalyzer.utils.Utils.createSettingsFile;

/**
 * Native-image JavaFX entry point.
 *
 * <p>This bootstrap class is used by the Gluon native profile to initialize
 * logging/runtime properties before showing {@link MainAppView}.</p>
 */
public class NativeEntryPoint extends Application {

    private static final Path STARTUP_TRACE_FILE = Path.of("/tmp", "dependency-analyzer-startup-trace.log");

    /**
     * Native process entry method.
     *
     * @param args process arguments passed to JavaFX launch
     */
    public static void main(String[] args) {
        configureSlf4jProvider();
        initializeBaseDirProperty(NativeEntryPoint.class);
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            startupProbe("Uncaught exception on thread " + thread.getName() + ": "
                + throwable.getClass().getName() + " - " + throwable.getMessage());
            throwable.printStackTrace(System.err);
        });
        startupProbe("NativeEntryPoint.main entered");
        startupProbe("Log file: " + getDefaultLogFilePath());
      Logger log = LoggerFactory.getLogger(NativeEntryPoint.class);
        log.info("Starting native JavaFX entry point");
        launch(NativeEntryPoint.class, args);
    }

    @Override
    public void init() {
        startupProbe("NativeEntryPoint.init entered");
    }

    /**
     * Starts the JavaFX stage after native bootstrap initialization.
     *
     * @param primaryStage JavaFX primary stage
     */
    @Override
    public void start(Stage primaryStage) {
        startupProbe("NativeEntryPoint.start entered");
        LoggerFactory.getLogger(NativeEntryPoint.class)
            .info("Initializing application settings and starting main view");
        createSettingsFile();
        initializeOverridesFile();
        new MainAppView().start(primaryStage);
        startupProbe("NativeEntryPoint.start completed");
        Platform.setImplicitExit(true);
    }

    private static void startupProbe(String message) {
      String line = "[startup] " + message + System.lineSeparator();
        System.err.print(line);
        try {
            Files.writeString(STARTUP_TRACE_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[startup] Failed writing startup trace file: " + e.getMessage());
        }
    }

    private static void configureSlf4jProvider() {
      String simpleProvider = "org.slf4j.simple.SimpleServiceProvider";
        if (classExists(simpleProvider)) {
            System.setProperty("slf4j.provider", simpleProvider);
            startupProbe("SLF4J provider forced to " + simpleProvider);
            return;
        }
      String logbackProvider = "ch.qos.logback.classic.spi.LogbackServiceProvider";
        System.setProperty("slf4j.provider", logbackProvider);
        startupProbe("SLF4J provider forced to " + logbackProvider);
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, NativeEntryPoint.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

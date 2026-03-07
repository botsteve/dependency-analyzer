package io.botsteve.dependencyanalyzer;


import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import io.botsteve.dependencyanalyzer.logging.MemoryLogger;
import io.botsteve.dependencyanalyzer.utils.LogUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.botsteve.dependencyanalyzer.utils.ScmRepositories.initializeOverridesFile;
import static io.botsteve.dependencyanalyzer.utils.Utils.createSettingsFile;
import static io.botsteve.dependencyanalyzer.views.LoginViewer.LOGIN_VIEWER;

public class DependencyAnalyzer extends Application {


    private ScheduledExecutorService scheduler;

    @Override
    public void start(Stage primaryStage) {
        LOGIN_VIEWER.showPasswordDialog(primaryStage);
        createSettingsFile();
        initializeOverridesFile();
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(MemoryLogger::logMemoryUsage, 0, 10, TimeUnit.SECONDS);

    }

    public static void main(String[] args) {
        LogUtils.initializeBaseDirProperty(DependencyAnalyzer.class);
        launch(args);
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        // Add any other cleanup code here
        Platform.exit();
        System.exit(0);
    }

}

package io.botsteve.dependencyanalyzer;

import io.botsteve.dependencyanalyzer.utils.LogUtils;

public class Launcher {
    public static void main(String[] args) {
        LogUtils.initializeBaseDirProperty(Launcher.class);
        DependencyAnalyzer.main(args);
    }
}

package io.botsteve.dependencyanalyzer.service;

import java.io.File;
import java.util.Arrays;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import io.botsteve.dependencyanalyzer.model.CollectingOutputHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenInvokerService {

  private static final Logger log = LoggerFactory.getLogger(MavenInvokerService.class);


  /**
   * Executes a Maven command through Maven Invoker API and returns captured console output.
   *
   * @param projectDir project root directory
   * @param moduleDir optional module subdirectory relative to project root
   * @param goals Maven goals/commands string
   * @param mavenOpts MAVEN_OPTS value passed to invocation
   * @param jdkPath JAVA_HOME value for the command process
   * @return collector containing Maven output lines
   */
  public static CollectingOutputHandler getMavenInvokerResult(String projectDir,
                                                              String moduleDir,
                                                              String goals,
                                                              String mavenOpts,
                                                              String jdkPath) {
    File toolchainsFile = null;
    toolchainsFile = new File(io.botsteve.dependencyanalyzer.utils.Utils.getRepositoriesPath(), "toolchains.xml");
    if (toolchainsFile.exists()) {
        goals += " --toolchains " + toolchainsFile.getAbsolutePath();
    }

    // Set Maven executable and home
    String mavenHome = System.getenv("MAVEN_HOME");
    if (mavenHome == null || mavenHome.isBlank()) {
        log.info("MAVEN_HOME not set, attempting to detect maven from PATH or common locations...");
        mavenHome = detectMavenHome();
    }

    if (mavenHome == null || mavenHome.isBlank()) {
        throw new DependencyAnalyzerException("MAVEN_HOME environment variable is not set. Please configure it to point to your Maven installation.");
    }

    File mavenHomeFile = new File(mavenHome);
    if (!mavenHomeFile.exists() || !mavenHomeFile.isDirectory()) {
         throw new DependencyAnalyzerException("MAVEN_HOME path is invalid: " + mavenHome);
    }

    String os = System.getProperty("os.name").toLowerCase();
    String mvnExecutableName = os.contains("win") ? "bin/mvn.cmd" : "bin/mvn";
    File mavenExecutable = new File(mavenHomeFile, mvnExecutableName);

    if (!mavenExecutable.exists()) {
        throw new DependencyAnalyzerException("Maven executable not found at: " + mavenExecutable.getAbsolutePath());
    }

    log.info("Maven home: {}", mavenHomeFile.getAbsolutePath());
    log.info("Maven executable: {}", mavenExecutable.getAbsolutePath());

    Invoker invoker = new DefaultInvoker();
    invoker.setMavenHome(mavenHomeFile);
    invoker.setMavenExecutable(mavenExecutable);

    InvocationRequest request = new DefaultInvocationRequest();
    File pomFile = new File(projectDir, moduleDir + "/pom.xml");
    request.setPomFile(pomFile);
    request.addArgs(Arrays.asList(goals.trim().split(" ")));
    request.setBatchMode(true);
    request.setDebug(true); // Enable verbose/debug output (-X)
    request.setMavenOpts(mavenOpts.trim());
    request.addShellEnvironment("JAVA_HOME", jdkPath);
    log.info("Invoking Maven: goals=[{}] opts=[{}] JAVA_HOME=[{}] pom=[{}]", goals, mavenOpts,
             jdkPath, pomFile.getAbsolutePath());

    var outputHandler = new CollectingOutputHandler();
    request.setOutputHandler(outputHandler);

    InvocationResult result = null;
    try {
      result = invoker.execute(request);
      log.info("Maven command finished with exit code: {}", result.getExitCode());

      if (result.getExitCode() != 0) {
        var depViewerException = new DependencyAnalyzerException("Build failed!");
        log.error("Build failed with exit code {}!", result.getExitCode(), depViewerException);
        throw depViewerException;
      }
    } catch (DependencyAnalyzerException e) {
      throw e;
    } catch (Exception e) {
      log.error("Maven invocation failed with exception", e);
      throw new DependencyAnalyzerException(e);
    }


    return outputHandler;
  }

  private static String detectMavenHome() {
    // 1. Check if 'mvn' is in the PATH
    String path = System.getenv("PATH");
    if (path != null) {
      String separator = File.pathSeparator;
      String[] dirs = path.split(separator);
      for (String dir : dirs) {
        File mvn = new File(dir, System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn");
        if (mvn.exists()) {
          // mavenHome is usually the parent of 'bin'
          File binDir = mvn.getParentFile();
          if (binDir != null && binDir.getName().equals("bin")) {
            return binDir.getParent();
          }
        }
      }
    }

    // 2. Check SDKMAN locations (common on Unix)
    String userHome = System.getProperty("user.home");
    File sdkmanMaven = new File(userHome, ".sdkman/candidates/maven/current");
    if (sdkmanMaven.exists() && sdkmanMaven.isDirectory()) {
      return sdkmanMaven.getAbsolutePath();
    }

    // 3. Check common Brew locations
    File brewMaven = new File("/usr/local/opt/maven/libexec");
    if (brewMaven.exists() && brewMaven.isDirectory()) {
      return brewMaven.getAbsolutePath();
    }
    File brewMavenArm = new File("/opt/homebrew/opt/maven/libexec");
    if (brewMavenArm.exists() && brewMavenArm.isDirectory()) {
      return brewMavenArm.getAbsolutePath();
    }

    return null;
  }
}

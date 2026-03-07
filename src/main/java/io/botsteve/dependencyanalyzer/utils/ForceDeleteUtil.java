package io.botsteve.dependencyanalyzer.utils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ForceDeleteUtil {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final boolean IS_POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 100;

    /**
     * Recursively deletes a directory, including read-only files, with retry and OS-specific fallbacks.
     *
     * @param directory directory to remove
     * @throws IOException when deletion fails after all retries and fallback attempts
     */
    public static void forceDeleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                forceDeleteFileWithRetry(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                forceDeleteFileWithRetry(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void forceDeleteFileWithRetry(Path path) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                forceDeleteFile(path);
                return; // Successful deletion
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while attempting to delete " + path, ie);
                    }
                }
            }
        }

        // If we've exhausted all attempts, try the external process as a last resort
        try {
            externalProcessDelete(path);
            return;
        } catch (IOException | InterruptedException e) {
            lastException = new IOException("Failed to delete after all attempts: " + path, e);
        }

        // If we've reached here, all attempts have failed
        throw lastException;
    }

    private static void forceDeleteFile(Path path) throws IOException {
        try {
            Files.delete(path);
        } catch (FileSystemException e) {
            if (IS_WINDOWS) {
                forceDeleteWindows(path);
            } else if (IS_POSIX) {
                forceDeletePosix(path);
            } else {
                path.toFile().setWritable(true);
            }
            // Retry deletion after modifying attributes/permissions
            Files.delete(path);
        }
    }

    private static void forceDeleteWindows(Path path) throws IOException {
        Files.setAttribute(path, "dos:readonly", false);
        Files.setAttribute(path, "dos:hidden", false);
        Files.setAttribute(path, "dos:system", false);
        Files.setAttribute(path, "dos:archive", false);
    }

    private static void forceDeletePosix(Path path) throws IOException {
      Set<PosixFilePermission> permissions = EnumSet.allOf(PosixFilePermission.class);
        Files.setPosixFilePermissions(path, permissions);
    }

    private static void externalProcessDelete(Path path) throws IOException, InterruptedException {
        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder("cmd.exe", "/c", "del", "/f", "/q", path.toString());
        } else {
            pb = new ProcessBuilder("rm", "-f", path.toString());
        }
      Process process = pb.start();
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("External process failed to delete: " + path);
        }
    }
}

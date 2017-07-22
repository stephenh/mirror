package mirror;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.TextFormat;

import mirror.MirrorGrpc.MirrorStub;

public class Utils {

  private static final Logger log = LoggerFactory.getLogger(Utils.class);

  @FunctionalInterface
  public interface InterruptedRunnable {
    void run() throws InterruptedException;
  }

  /** grpc-java doesn't support timeouts yet, so we have to set a per-call deadline. */
  public static MirrorStub withTimeout(MirrorStub s) {
    // over VPN, ~100k files can take 30 seconds.
    return s.withDeadlineAfter(3, TimeUnit.MINUTES);
  }

  public static void resetIfInterrupted(InterruptedRunnable r) {
    try {
      r.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  public static String debugString(Update u) {
    return "[" + TextFormat.shortDebugString(u).replace(": ", ":") + "]";
  }

  public static void time(Logger log, String action, Runnable r) {
    log.info("Starting " + action);
    long start = System.currentTimeMillis();
    r.run();
    long stop = System.currentTimeMillis();
    log.info("Completed " + action + ": " + (stop - start) + "ms");
  }

  /** Waits until {@code path} is not longer being actively written to (best-effort). */
  public static void ensureSettled(FileAccess fileAccess, Path path) throws InterruptedException {
    // do some gyrations to ensure the file writer has completely written the file
    boolean shouldBeComplete = false;
    try {
      while (!shouldBeComplete) {
        long localModTime = fileAccess.getModifiedTime(path);
        if (fileWasJustModified(localModTime)) {
          long size1 = fileAccess.getFileSize(path);
          Thread.sleep(500); // 100ms was too small
          long size2 = fileAccess.getFileSize(path);
          shouldBeComplete = size1 == size2;
          if (!shouldBeComplete) {
            log.debug("{} not settled {} {}", path, size1, size2);
          }
        } else {
          shouldBeComplete = true; // no need to check
        }
      }
    } catch (IOException io) {
      // assume the file disappeared, and we'll catch it later
      return;
    }
  }

  /** @return whether localModTime was within the last 2 seconds. */
  private static boolean fileWasJustModified(long localModTime) {
    return (System.currentTimeMillis() - localModTime) <= 2000;
  }

  public static String abbreviatePath(String path) {
    if (StringUtils.countMatches(path, '/') < 2) {
      return path;
    } else {
      return StringUtils.substringBefore(path, "/") + "/.../" + StringUtils.substringAfterLast(path, "/");
    }
  }

}

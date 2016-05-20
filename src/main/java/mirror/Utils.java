package mirror;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;

import mirror.MirrorGrpc.MirrorStub;

public class Utils {

  private static final Logger log = LoggerFactory.getLogger(Utils.class);

  @FunctionalInterface
  public interface InterruptedSupplier<T> {
    T get() throws InterruptedException;
  }

  @FunctionalInterface
  public interface InterruptedRunnable {
    void run() throws InterruptedException;
  }

  @FunctionalInterface
  public interface InterruptedConsumer<T> {
    void consume(T value) throws InterruptedException;
  }

  /** grpc-java doesn't support timeouts yet, so we have to set a per-call deadline. */
  public static MirrorStub withTimeout(MirrorStub s) {
    return s.withDeadlineAfter(30, TimeUnit.SECONDS);
  }

  public static void handleInterrupt(InterruptedRunnable r) {
    try {
      r.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  public static <T> T handleInterrupt(InterruptedSupplier<T> f) {
    try {
      return f.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  public static <T> Consumer<T> handleInterrupt(final InterruptedConsumer<T> c) {
    return new Consumer<T>() {
      @Override
      public void accept(T t) {
        try {
          c.consume(t);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
    };
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

  /** @return the contents of {@code path}, after doing a best-effort attempt to ensure it's done being written. */
  public static ByteString readDataFully(FileAccess fileAccess, Path path) throws IOException, InterruptedException {
    // do some gyrations to ensure the file writer has completely written the file
    boolean shouldBeComplete = false;
    while (!shouldBeComplete) {
      long localModTime = fileAccess.getModifiedTime(path);
      long size1 = fileAccess.getFileSize(path);
      if (fileWasJustModified(localModTime)) {
        Thread.sleep(500); // 100ms was too small
        localModTime = fileAccess.getModifiedTime(path);
        long size2 = fileAccess.getFileSize(path);
        log.info("...waiting {} {}", size1, size2);
        shouldBeComplete = size1 == size2;
      } else {
        shouldBeComplete = true; // if seeded data, assume we don't need to sleep
      }
    }
    return fileAccess.read(path);
  }

  /** @return whether localModTime was within the last 2 seconds. */
  private static boolean fileWasJustModified(long localModTime) {
    return (System.currentTimeMillis() - localModTime) <= 2000;
  }

}

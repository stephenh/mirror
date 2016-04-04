package mirror;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;

public class Utils {

  private static final Logger log = LoggerFactory.getLogger(Utils.class);

  public static String debugString(Update u) {
    return "[" + TextFormat.shortDebugString(u).replace(": ", ":") + "]";
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

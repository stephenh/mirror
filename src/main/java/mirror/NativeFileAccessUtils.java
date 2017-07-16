package mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;

import jnr.posix.FileStat;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;

public class NativeFileAccessUtils {

  private static final POSIX posix = POSIXFactory.getNativePOSIX();

  @VisibleForTesting
  public static void setModifiedTimeForSymlink(Path absolutePath, long millis) throws IOException {
    long[] modTime = millisToTimeStructArray(millis);
    int r = posix.lutimes(absolutePath.toString(), modTime, modTime);
    if (r != 0) {
      throw new IOException("lutimes failed with code " + r);
    }
  }

  public static void setReadOnly(Path absolutePath) {
    posix.chmod(absolutePath.toFile().toString(), Integer.parseInt("0444", 8));
  }

  public static void setWritable(Path absolutePath) {
    FileStat s = posix.stat(absolutePath.toFile().toString());
    posix.chmod(absolutePath.toFile().toString(), s.mode() | Integer.parseInt("0700", 8));
  }

  public static boolean isExecutable(Path absolutePath) throws IOException {
    Set<PosixFilePermission> p = Files.getPosixFilePermissions(absolutePath);
    return p.contains(PosixFilePermission.GROUP_EXECUTE)
      || p.contains(PosixFilePermission.OWNER_EXECUTE)
      || p.contains(PosixFilePermission.OTHERS_EXECUTE);
  }

  public static void setExecutable(Path absolutePath) throws IOException {
    Set<PosixFilePermission> p = Files.getPosixFilePermissions(absolutePath);
    p.add(PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(absolutePath, p);
  }

  /** @return millis has an array of seconds + microseconds, as expected by the POSIX APIs. */
  private static long[] millisToTimeStructArray(long millis) {
    return new long[] { millis / 1000, (millis % 1000) * 1000 };
  }
}

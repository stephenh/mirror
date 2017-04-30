package mirror;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.protobuf.ByteString;

import jnr.posix.FileStat;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;

public class NativeFileAccess implements FileAccess {

  private static final Logger log = LoggerFactory.getLogger(NativeFileAccess.class);
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

  public static void main(String[] args) throws Exception {
    Path root = Paths.get("/home/stephen/dir1");
    NativeFileAccess f = new NativeFileAccess(root);
    Path bar = Paths.get("bar.txt");
    ByteString b = f.read(bar);
    String s = Charsets.US_ASCII.newDecoder().decode(ByteBuffer.wrap(b.toByteArray())).toString();
    System.out.println(s);
    f.write(bar, ByteBuffer.wrap((s + "2").getBytes()));
    f.setModifiedTime(bar, System.currentTimeMillis());
    System.out.println(root.resolve(bar).toFile().lastModified());
  }

  private final Path rootDirectory;

  public NativeFileAccess(Path rootDirectory) {
    this.rootDirectory = rootDirectory;
  }

  @Override
  public void write(Path relative, ByteBuffer data) throws IOException {
    Path path = rootDirectory.resolve(relative);
    mkdir(path.getParent().toAbsolutePath());
    try {
      doWrite(data, path);
    } catch (AccessDeniedException ade) {
      // sometimes code generators mark files as read-only; for now just assume
      // our "newer always wins" logic is correct, and try to write it anyway
      setWritable(path);
      doWrite(data, path);
    }
  }

  @Override
  public ByteString read(Path relative) throws IOException {
    try (FileInputStream fis = new FileInputStream(resolve(relative).toFile())) {
      return ByteString.readFrom(fis);
    }
  }

  @Override
  public long getModifiedTime(Path relative) throws IOException {
    return Files.getLastModifiedTime(resolve(relative), LinkOption.NOFOLLOW_LINKS).toMillis();
  }

  @Override
  public void setModifiedTime(Path relative, long millis) throws IOException {
    setModifiedTimeForSymlink(resolve(relative).toAbsolutePath(), millis);
  }

  @Override
  public void delete(Path relative) throws IOException {
    File file = resolve(relative).toFile();
    if (file.isDirectory()) {
      FileUtils.deleteDirectory(file);
    } else {
      file.delete();
    }
  }

  @Override
  public void createSymlink(Path relative, Path target) throws IOException {
    Path path = resolve(relative);
    path.getParent().toFile().mkdirs();
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      Files.delete(path);
    }
    Files.createSymbolicLink(path, target);
  }

  @Override
  public boolean isSymlink(Path relativePath) {
    return Files.isSymbolicLink(resolve(relativePath));
  }

  @Override
  public Path readSymlink(Path relativePath) throws IOException {
    // symlink semantics is that the path is relative to the location of the link
    // path (relativePath), so we don't want to return it relative to the rootDirectory
    Path path = resolve(relativePath);
    Path parent = path.getParent();
    Path symlink = Files.readSymbolicLink(path);
    if (symlink.isAbsolute()) {
      Path p = parent.toAbsolutePath().relativize(symlink);
      log.debug("Read absolute symlink {} as {}, returning {}", relativePath, symlink, p);
      return p;
    } else {
      Path target = parent.resolve(symlink);
      Path p = parent.relativize(target);
      log.debug("Read relative symlink {} as {}, returning {}", relativePath, symlink, p);
      return p;
    }
  }

  @Override
  public boolean exists(Path relativePath) {
    return resolve(relativePath).toFile().exists();
  }

  private Path resolve(Path relativePath) {
    return rootDirectory.resolve(relativePath);
  }

  /** @return millis has an array of seconds + microseconds, as expected by the POSIX APIs. */
  private static long[] millisToTimeStructArray(long millis) {
    return new long[] { millis / 1000, (millis % 1000) * 1000 };
  }

  @Override
  public long getFileSize(Path relativePath) throws IOException {
    return resolve(relativePath).toFile().length();
  }

  @Override
  public void mkdir(Path relativePath) throws IOException {
    mkdirImpl(resolve(relativePath).toAbsolutePath());
  }

  @Override
  public boolean isDirectory(Path relativePath) {
    return resolve(relativePath).toFile().isDirectory();
  }

  private static void doWrite(ByteBuffer data, Path path) throws IOException {
    FileChannel c = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    try {
      c.write(data);
    } finally {
      c.close();
    }
  }

  /** @param path the absolute path of the directory to create */
  private static void mkdirImpl(Path path) throws IOException {
    path.toFile().mkdirs();
    if (!path.toFile().exists()) {
      // it could be that relative has a parent that used to be a symlink, but now is not anymore...
      boolean foundOldSymlink = false;
      Path current = path;
      while (current != null) {
        if (Files.isSymbolicLink(current)) {
          current.toFile().delete();
          path.toFile().mkdirs();
          foundOldSymlink = true;
        }
        current = current.getParent();
      }
      if (!foundOldSymlink) {
        throw new IOException("Could not create directory " + path);
      }
    }
  }

}

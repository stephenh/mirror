package mirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

public class NativeFileAccess implements FileAccess {

  public static void main(String[] args) throws Exception {
    NativeFileAccess f = new NativeFileAccess(Paths.get("/home/stephen/dir1"));
    Path bar = Paths.get("bar.txt");
    ByteBuffer b = f.read(bar);
    String s = Charsets.US_ASCII.newDecoder().decode(b).toString();
    System.out.println(s);
    f.write(bar, ByteBuffer.wrap((s + "2").getBytes()));
    // f.setModifiedTime(bar, 86_002);
  }

  private final Path rootDirectory;

  public NativeFileAccess(Path rootDirectory) {
    this.rootDirectory = rootDirectory;
  }

  @Override
  public void write(Path relative, ByteBuffer data) throws IOException {
    Path path = rootDirectory.resolve(relative);
    path.getParent().toFile().mkdirs();
    FileChannel c = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    try {
      c.write(data);
    } finally {
      c.close();
    }
  }

  @Override
  public ByteBuffer read(Path relative) throws IOException {
    return Files.map(resolve(relative).toFile());
  }

  @Override
  public long getModifiedTime(Path relative) {
    return resolve(relative).toFile().lastModified();
  }

  @Override
  public void setModifiedTime(Path relative, long time) throws IOException {
    resolve(relative).toFile().setLastModified(time);
  }

  @Override
  public void delete(Path relative) throws IOException {
    resolve(relative).toFile().delete();
  }

  @Override
  public void createSymlink(Path relative, Path target) throws IOException {
    resolve(relative).getParent().toFile().mkdirs();
    java.nio.file.Files.createSymbolicLink(resolve(relative), target);
  }

  @Override
  public boolean isSymlink(Path relativePath) throws IOException {
    return java.nio.file.Files.isSymbolicLink(resolve(relativePath));
  }

  @Override
  public Path readSymlink(Path relativePath) throws IOException {
    // symlink semantics is that the path is relative to the location of the link
    // path (relativePath), so we don't want to return it relative to the rootDirectory
    Path symlink = java.nio.file.Files.readSymbolicLink(resolve(relativePath));
    if (symlink.isAbsolute()) {
      return resolve(relativePath).getParent().toAbsolutePath().relativize(symlink);
    } else {
      return resolve(relativePath).getParent().relativize(symlink);
    }
  }

  private Path resolve(Path relativePath) {
    return rootDirectory.resolve(relativePath);
  }

}

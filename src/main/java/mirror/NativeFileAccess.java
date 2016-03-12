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
    Path path = rootDirectory.resolve(relative);
    return Files.map(path.toFile());
  }

  @Override
  public long getModifiedTime(Path relative) {
    Path path = rootDirectory.resolve(relative);
    return path.toFile().lastModified();
  }

  @Override
  public void setModifiedTime(Path relative, long time) throws IOException {
    Path path = rootDirectory.resolve(relative);
    path.toFile().setLastModified(time);
  }

  @Override
  public void delete(Path relative) throws IOException {
    Path path = rootDirectory.resolve(relative);
    path.toFile().delete();
  }

  @Override
  public void createSymlink(Path relative, Path target) throws IOException {
    Path path = rootDirectory.resolve(relative);
    java.nio.file.Files.createSymbolicLink(path, target);
  }

}

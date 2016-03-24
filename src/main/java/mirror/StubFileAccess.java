package mirror;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;

public class StubFileAccess implements FileAccess {

  private Map<Path, byte[]> fileData = new HashMap<>();
  private Map<Path, Long> fileTimes = new HashMap<>();
  private List<Path> deleted = new ArrayList<>();
  private Map<Path, Path> symlinks = new HashMap<>();

  @Override
  public void write(Path path, ByteBuffer data) throws IOException {
    byte[] copy = new byte[data.remaining()];
    data.get(copy, 0, data.remaining());
    fileData.put(path, copy);
    fileTimes.put(path, 1L);
  }

  public void write(Path path, ByteBuffer data, long modTime) throws IOException {
    write(path, data);
    fileTimes.put(path, modTime);
  }

  @Override
  public ByteString read(Path path) throws IOException {
    byte[] data = fileData.get(path);
    if (data == null) {
      throw new FileNotFoundException(path.toString());
    }
    return ByteString.copyFrom(data);
  }

  @Override
  public long getModifiedTime(Path path) throws IOException {
    Long modTime = fileTimes.get(path);
    if (modTime == null) {
      throw new FileNotFoundException(path.toString());
    }
    return modTime.longValue();
  }

  @Override
  public void setModifiedTime(Path path, long time) throws IOException {
    fileTimes.put(path, time);
  }

  @Override
  public void delete(Path path) throws IOException {
    deleted.add(path);
    // could delete all subpaths if we needed to, but doesn't matter
    // for current tests
    fileData.remove(path);
    fileTimes.remove(path);
  }

  @Override
  public boolean exists(Path path) {
    return fileTimes.keySet().contains(path);
  }

  public boolean wasDeleted(Path path) {
    return deleted.contains(path);
  }

  @Override
  public Path readSymlink(Path link) {
    return symlinks.get(link);
  }

  @Override
  public boolean isSymlink(Path link) {
    return symlinks.get(link) != null;
  }

  @Override
  public void createSymlink(Path link, Path target) throws IOException {
    symlinks.put(link, target);
    fileTimes.put(link, 1L);
  }

}

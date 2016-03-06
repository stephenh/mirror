package mirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StubFileAccess implements FileAccess {

  private Map<Path, byte[]> fileData = new HashMap<>();
  private Map<Path, Long> fileTimes = new HashMap<>();
  private List<Path> deleted = new ArrayList<>();

  @Override
  public void write(Path path, ByteBuffer data) throws IOException {
    byte[] copy = new byte[data.remaining()];
    data.get(copy, 0, data.remaining());
    fileData.put(path, copy);
    fileTimes.put(path, 1L);
  }

  @Override
  public ByteBuffer read(Path path) throws IOException {
    return ByteBuffer.wrap(fileData.get(path));
  }

  @Override
  public long getModifiedTime(Path path) throws IOException {
    return fileTimes.get(path);
  }

  @Override
  public void delete(Path path) throws IOException {
    deleted.add(path);
    // could delete all subpaths if we needed to, but doesn't matter
    // for current tests
    fileData.remove(path);
    fileTimes.remove(path);
  }

  public boolean wasDeleted(Path path) {
    return deleted.contains(path);
  }

}

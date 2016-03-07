package mirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * An interfacing for reads/writes.
 * 
 * This is facilitates testing by using the {@link StubFileAccess}.
 */
public interface FileAccess {

  ByteBuffer read(Path path) throws IOException;

  void write(Path path, ByteBuffer data) throws IOException;

  void delete(Path path) throws IOException;

  long getModifiedTime(Path path) throws IOException;

  void setModifiedTime(Path path, long time) throws IOException;

}

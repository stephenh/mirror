package mirror.watchman;

import java.io.IOException;
import java.util.Map;

/**
 * Watchman interface.
 */
public interface Watchman extends AutoCloseable {

  Map<String, Object> query(Object... query) throws IOException;

  Map<String, Object> read() throws IOException;

}

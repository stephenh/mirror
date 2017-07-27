package mirror.watchman;

import java.io.IOException;

/** Creates a new connection to the local watchman daemon. */
public interface WatchmanFactory {

  Watchman newWatchman() throws IOException;

}

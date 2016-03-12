package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Holds the last-known state of paths.
 *
 * E.g. the server has a PathState instance for the last-known state
 * of the client, and the client has a PathState instance for the last-known
 * state of the server.
 *
 * The initial PathStates are also used to bring the server/client into
 * intial sync before starting the two-way streaming sync.
 */
public class PathState {

  private final Map<Path, Long> paths = new LinkedHashMap<>();

  public PathState() {
    this(new ArrayList<>());
  }

  public PathState(List<Update> updates) {
    for (Update update : updates) {
      paths.put(Paths.get(update.getPath()), update.getModTime());
    }
  }

  public void add(PathState other) {
    this.paths.putAll(other.paths);
  }
  
  /** Records {@code remoteModTime} as the last-known mod time for {@code path}. */
  public void record(Path path, long modTime) {
    paths.put(path, modTime);
  }

  /** @return if we think {@code path} is older than {@code potentiallyNewerModTime}. */
  public boolean needsUpdate(Path path, long potentiallyNewerModTime) {
    Long modTime = paths.get(path);
    return modTime == null || modTime.longValue() < potentiallyNewerModTime;
  }
  
  /** @return if we think {@code path} exists on the remote side. */
  public boolean needsDeleted(Path path) {
    Long modTime = paths.get(path);
    return modTime != null && modTime.longValue() > -1;
  }

  /** @return given {@code otherState}, return the paths from this state that are out-of-date or missing. */
  public List<String> getPathsToFetch(PathState otherState) {
    List<String> fetch = new ArrayList<>();
    // e.g. for every path on the remote side, is it stale/missing on our side?
    for (Entry<Path, Long> entry : otherState.paths.entrySet()) {
      if (needsUpdate(entry.getKey(), entry.getValue())) {
        fetch.add(entry.getKey().toString());
      }
    }
    return fetch;
  }
}

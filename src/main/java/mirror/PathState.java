package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the last-known state of paths.
 *
 * E.g. the server has a PathState instance for the last-known state
 * of the client, and the client has a PathState instance for the last-known
 * state of the server.
 *
 * The initial PathStates are also used to bring the server/client into
 * initial sync before starting the two-way streaming sync.
 */
public class PathState {

  private static final Logger log = LoggerFactory.getLogger(PathState.class);
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
    log.debug("{} mod time = {}", path, modTime);
    paths.put(path, modTime);
  }

  /** @return if we think {@code path} is older than {@code potentiallyNewerModTime}. */
  public boolean needsUpdate(Path path, long potentiallyNewerModTime) {
    Long modTime = paths.get(path);
    boolean needsUpdate = modTime == null || modTime.longValue() < potentiallyNewerModTime;
    log.debug("{} has mod time {} vs. {} so needsUpdate={}", path, modTime, potentiallyNewerModTime, needsUpdate);
    if (path.toString().contains("ChimeraQuota")) {
      log.info("{} has mod time {} vs. {} so needsUpdate={}", path, modTime, potentiallyNewerModTime, needsUpdate);
    }
    return needsUpdate;
  }

  /** @return if we think {@code path} exists on the remote side. */
  public boolean needsDeleted(Path path) {
    Long modTime = paths.get(path);
    boolean needsDeleted = modTime != null && modTime.longValue() > -1;
    log.debug("{} has mod time {} so needsDeleted={}", path, modTime, needsDeleted);
    return needsDeleted;
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

  public int size() {
    return paths.size();
  }
}

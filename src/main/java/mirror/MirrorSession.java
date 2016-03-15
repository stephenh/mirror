package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;

/**
 * Represents a session of an initial sync plus on-going synchronization of
 * our local file changes with a remote session.
 *
 * Note that the session is used on both the server and client, e.g. upon
 * connection, the server will instantiate a MirrorSession to talk to the client,
 * and the client will also instantiate it's own MirrorSession to talk to the
 * server.
 *
 * Once the two MirrorSessions on each side are instantiated, the server
 * and client are basically just peers using the same logic/implementation
 * to share changes.
 */
public class MirrorSession {

  private static final Logger log = LoggerFactory.getLogger(MirrorSession.class);
  private final FileAccess fs;
  private final BlockingQueue<Update> queue = new ArrayBlockingQueue<>(1_000_000);
  private final FileWatcher watcher;
  private SyncLogic sync;
  private PathState initialRemoteState;

  public MirrorSession(Path root) {
    this.fs = new NativeFileAccess(root);
    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      watcher = new FileWatcher(watchService, root, queue, new StandardExcludeFilter());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void addRemoteUpdate(Update update) {
    queue.add(update);
  }

  public List<Update> calcInitialState() throws IOException, InterruptedException {
    List<Update> initial = watcher.performInitialScan();
    // We've drained the initial state, so we can tell FileWatcher to start polling now.
    // This will start filling up the queue, but not technically start processing/sending
    // updates to the remote (see #startPolling).
    watcher.startPolling();
    return new InitialState(fs).prepare(initial);
  }

  public void setInitialRemoteState(PathState initialRemoteState) {
    this.initialRemoteState = initialRemoteState;
  }

  /** Pretend we have local file events for anything the remote side needs from us. */
  public void seedQueueForInitialSync(PathState initialLocalState) throws IOException {
    List<String> paths = this.initialRemoteState.getPathsToFetch(initialLocalState);
    log.info("Queueing {} paths to send to the remote host", paths.size());
    for (String path : paths) {
      log.debug("Seeding {}", path);
      Path p = Paths.get(path);
      if (fs.isSymlink(p)) {
        queue.add(Update.newBuilder().setPath(path).setLocal(true).setSymlink(fs.readSymlink(p).toString()).build());
      } else {
        queue.add(Update.newBuilder().setPath(path).setLocal(true).build());
      }
    }
  }

  public void startPolling(StreamObserver<Update> outgoingChanges) throws IOException {
    sync = new SyncLogic(queue, outgoingChanges, fs);
    sync.addRemoteState(initialRemoteState);
    sync.startPolling();
  }

  public void stop() throws InterruptedException {
    sync.stop();
  }
}

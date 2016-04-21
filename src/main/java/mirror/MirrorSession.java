package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import io.grpc.stub.StreamObserver;
import mirror.UpdateTreeDiff.DiffResults;

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

  private final FileAccess fileAccess;
  private final BlockingQueue<Update> queue = new ArrayBlockingQueue<>(1_000_000);
  private final FileWatcher watcher;
  private final UpdateTree tree = UpdateTree.newRoot();
  private final String role;
  private SyncLogic sync;

  public MirrorSession(String role, Path root) {
    this.role = role;
    this.fileAccess = new NativeFileAccess(root);
    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      watcher = new FileWatcher(watchService, root, queue);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void addRemoteUpdate(Update update) {
    queue.add(update);
  }

  public List<Update> calcInitialState() throws IOException, InterruptedException {
    List<Update> initialUpdates = watcher.performInitialScan();
    initialUpdates.forEach(u -> tree.addLocal(u));
    // We've drained the initial state, so we can tell FileWatcher to start polling now.
    // This will start filling up the queue, but not technically start processing/sending
    // updates to the remote (see #startPolling).
    watcher.startWatching();
    return initialUpdates;
  }

  public void addInitialRemoteUpdates(List<Update> remoteInitialUpdates) {
    remoteInitialUpdates.forEach(u -> tree.addRemote(u));
  }

  /** Pretend we have local file events for anything the remote side needs from us. */
  public void initialSync(StreamObserver<Update> outgoingChanges) throws IOException {
    DiffResults r = new UpdateTreeDiff(tree).diff();
    new DiffApplier(role, outgoingChanges, fileAccess).apply(r);
  }

  public void startPolling(StreamObserver<Update> outgoingChanges) throws IOException {
    sync = new SyncLogic(role, queue, outgoingChanges, fileAccess, tree);
    sync.startPolling();
  }

  public void stop() throws InterruptedException {
    sync.stop();
  }
}

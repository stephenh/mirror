package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.List;

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

  private final Logger log = LoggerFactory.getLogger(MirrorSession.class);
  private final FileAccess fileAccess;
  private final Queues queues = new Queues();
  private final MirrorSessionState state = new MirrorSessionState();
  private final QueueWatcher queueWatcher = new QueueWatcher(state, queues);
  private final SaveToLocal saveToLocal;
  private final FileWatcher watcher;
  private final UpdateTree tree = UpdateTree.newRoot();
  private final SyncLogic sync;
  private SaveToRemote saveToRemote;
  private StreamObserver<Update> outgoingChanges;

  public MirrorSession(Path root) {
    this.fileAccess = new NativeFileAccess(root);
    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      watcher = new FileWatcher(state, watchService, root, queues.incomingQueue);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    sync = new SyncLogic(state, queues, fileAccess, tree);
    queueWatcher.start();
    saveToLocal = new SaveToLocal(state, queues, fileAccess);
    saveToLocal.start();
    state.addStoppedCallback(() -> {
      if (outgoingChanges != null) {
        outgoingChanges.onCompleted();
      }
      watcher.stop();
      sync.stop();
      saveToLocal.stop();
      queueWatcher.stop();
      if (saveToRemote != null) {
        saveToRemote.stop();
      }
    });
  }

  public void addRemoteUpdate(Update update) {
    queues.incomingQueue.add(update);
  }

  public void addStoppedCallback(Runnable r) {
    state.addStoppedCallback(r);
  }

  public List<Update> calcInitialState() throws IOException, InterruptedException {
    List<Update> initialUpdates = watcher.performInitialScan();
    initialUpdates.forEach(u -> tree.addLocal(u));
    // We've drained the initial state, so we can tell FileWatcher to start polling now.
    // This will start filling up the queue, but not technically start processing/sending
    // updates to the remote (see #startPolling).
    watcher.start();
    return initialUpdates;
  }

  public void addInitialRemoteUpdates(List<Update> remoteInitialUpdates) {
    remoteInitialUpdates.forEach(u -> tree.addRemote(u));
  }

  public void diffAndStartPolling(StreamObserver<Update> outgoingChanges) {
    this.outgoingChanges = outgoingChanges;
    sync.start();
    saveToRemote = new SaveToRemote(state, queues, fileAccess, outgoingChanges);
    saveToRemote.start();
  }

  public void stop() {
    log.info("Stopping session");
    state.stop();
  }
}

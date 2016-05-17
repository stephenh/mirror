package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.List;

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

  private final FileAccess fileAccess;
  private final Queues queues = new Queues();
  private final QueueWatcher queueWatcher = new QueueWatcher(queues);
  private StreamObserver<Update> outgoingChanges;
  private final FileWatcher watcher;
  private final UpdateTree tree = UpdateTree.newRoot();
  private final SyncLogic sync;
  private ResultsSender sender;

  public MirrorSession(String role, Path root) {
    this.fileAccess = new NativeFileAccess(root);
    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      watcher = new FileWatcher(watchService, root, queues.incomingQueue);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    sync = new SyncLogic(role, queues, fileAccess, tree);
    queueWatcher.startWatching();
  }

  public void addRemoteUpdate(Update update) {
    queues.incomingQueue.add(update);
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

  public void diffAndStartPolling(StreamObserver<Update> outgoingChanges) throws IOException {
    this.outgoingChanges = outgoingChanges;
    sync.startPolling();
    sender = new ResultsSender("", queues.resultQueue, outgoingChanges, fileAccess);
    sender.startSending();
  }

  public void stop() throws InterruptedException, IOException {
    if (outgoingChanges != null) {
      outgoingChanges.onCompleted();
    }
    watcher.stop();
    sync.stop();
    sender.stop();
    queueWatcher.stop();
  }
}

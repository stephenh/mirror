package mirror;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.ArrayList;
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
  private final QueueWatcher queueWatcher = new QueueWatcher(queues);
  private final SaveToLocal saveToLocal;
  private final FileWatcher fileWatcher;
  private final UpdateTree tree = UpdateTree.newRoot();
  private final SyncLogic syncLogic;
  private SaveToRemote saveToRemote;
  private StreamObserver<Update> outgoingChanges;

  public MirrorSession(Path root, FileSystem fileSystem) {
    this(root, new NativeFileAccess(root), new WatchServiceFileWatcher(newWatchService(fileSystem), root));
  }

  public MirrorSession(Path root, FileAccess fileAccess, FileWatcher fileWatcher) {
    this.fileAccess = fileAccess;
    this.fileWatcher = fileWatcher;
    syncLogic = new SyncLogic(queues, fileAccess, tree);
    queueWatcher.start(state.stopOnFailure());
    saveToLocal = new SaveToLocal(queues, fileAccess);
    saveToLocal.start(state.stopOnFailure());
    // use separate callbacks so one failing doesn't stop the others
    state.addStoppedCallback(() -> fileWatcher.stop());
    state.addStoppedCallback(() -> syncLogic.stop());
    state.addStoppedCallback(() -> saveToLocal.stop());
    state.addStoppedCallback(() -> queueWatcher.stop());
    state.addStoppedCallback(() -> {
      if (saveToRemote != null) {
        saveToRemote.stop();
      }
      if (outgoingChanges != null) {
        outgoingChanges.onCompleted();
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
    List<Update> initialUpdates = fileWatcher.performInitialScan(queues.incomingQueue);
    // We've drained the initial state, so we can tell FileWatcher to start polling now.
    // This will start filling up the queue, but not technically start processing/sending
    // updates to the remote (see #startPolling).
    fileWatcher.start(state.stopOnFailure());

    initialUpdates.forEach(u -> tree.addLocal(u));

    // only sync non-ignored files
    List<Update> seedRemote = new ArrayList<>();
    tree.visit(n -> {
      if (n.getLocal() != null && !n.shouldIgnore()) {
        seedRemote.add(n.getLocal());
      }
    });
    return seedRemote;
  }

  public void addInitialRemoteUpdates(List<Update> remoteInitialUpdates) {
    remoteInitialUpdates.forEach(u -> {
      // if a file, mark it has an initial sync, so we know not to save it
      // it until we get the real update with the data filled in
      if (UpdateTree.isFile(u)) {
        u = Update.newBuilder(u).setData(UpdateTree.initialSyncMarker).build();
      }
      tree.addRemote(u);
    });
  }

  public void diffAndStartPolling(StreamObserver<Update> outgoingChanges) {
    this.outgoingChanges = outgoingChanges;
    syncLogic.start(state.stopOnFailure());
    saveToRemote = new SaveToRemote(queues, fileAccess, outgoingChanges);
    saveToRemote.start(state.stopOnFailure());
  }

  public void stop() {
    log.info("Stopping session");
    state.stop();
  }

  private static WatchService newWatchService(FileSystem fileSystem) {
    try {
      return fileSystem.newWatchService();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

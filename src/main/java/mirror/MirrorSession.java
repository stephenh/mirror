package mirror;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.StatusRuntimeException;
import mirror.tasks.TaskFactory;
import mirror.tasks.TaskLogic;
import mirror.tasks.TaskPool;

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
  private final TaskPool taskPool;
  private final FileAccess fileAccess;
  private final Queues queues = new Queues();
  private final QueueWatcher queueWatcher = new QueueWatcher(queues);
  private final SaveToLocal saveToLocal;
  private final FileWatcher fileWatcher;
  private final UpdateTree tree;
  private final SyncLogic syncLogic;
  private volatile SaveToRemote saveToRemote;
  private volatile OutgoingConnection outgoingChanges;

  public MirrorSession(TaskFactory taskFactory, MirrorPaths paths, FileAccess fileAccess, FileWatcherFactory fileWatcherFactory) {
    this.fileAccess = fileAccess;
    this.fileWatcher = fileWatcherFactory.newWatcher(paths, queues.incomingQueue);
    this.tree = UpdateTree.newRoot(paths);

    // Run all our tasks in a pool so they are terminated together
    taskPool = taskFactory.newTaskPool();

    syncLogic = new SyncLogic(queues, fileAccess, tree);
    // started in diffAndStartPolling

    saveToLocal = new SaveToLocal(queues, fileAccess);
    start(saveToLocal);

    start(queueWatcher);

    taskPool.addShutdownCallback(() -> {
      if (outgoingChanges != null) {
        try {
          outgoingChanges.closeConnection();
        } catch (StatusRuntimeException e) {
          // already disconnected/cancelled
        }
      }
    });
  }

  public void addRemoteUpdate(Update update) {
    queues.incomingQueue.add(update);
  }

  public void addStoppedCallback(Runnable callback) {
    taskPool.addShutdownCallback(callback);
  }

  public List<Update> calcInitialState() throws IOException, InterruptedException {
    List<Update> initialUpdates = fileWatcher.performInitialScan();

    // We've drained the initial state, so we can tell FileWatcher to start polling now.
    // This will start filling up the queue, but not technically start processing/sending
    // updates to the remote (see #startPolling).
    start(fileWatcher);

    initialUpdates.forEach(u -> tree.addLocal(u));

    // only sync non-ignored files
    List<Update> seedRemote = new ArrayList<>();
    // Make sure to visitAll as otherwise our extra-included files (e.g. snapshot
    // jars inside an otherwise-ignored directory) won't get sent and the remote
    // side will think we don't have any of them.
    tree.visitAll(n -> {
      if (n.getLocal() != null && !n.shouldIgnore()) {
        seedRemote.add(n.restorePath(n.getLocal()));
      }
    });
    return seedRemote;
  }

  public void addInitialRemoteUpdates(List<Update> remoteInitialUpdates) {
    remoteInitialUpdates.forEach(u -> {
      // if a file, mark it has an initial sync, so we know not to save it
      // it until we get the real update with the data filled in
      if (UpdateTree.isFile(u)) {
        u = u.toBuilder().setData(UpdateTree.initialSyncMarker).build();
      }
      tree.addRemote(u);
    });
  }

  public void diffAndStartPolling(OutgoingConnection outgoingChanges) {
    this.outgoingChanges = outgoingChanges;

    start(syncLogic);

    saveToRemote = new SaveToRemote(queues, fileAccess, outgoingChanges);
    start(saveToRemote);
  }

  public void stop() {
    log.info("Stopping session");
    // this won't block; could potentially add a CountDownLatch
    taskPool.stopAllTasks();
  }

  private void start(TaskLogic logic) {
    taskPool.runTask(logic);
  }
}

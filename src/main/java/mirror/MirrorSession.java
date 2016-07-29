package mirror;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import mirror.tasks.TaskFactory;
import mirror.tasks.TaskLogic;
import mirror.tasks.ThreadBasedTaskFactory;

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
  private final Clock clock;
  private final TaskFactory taskFactory;
  private final FileAccess fileAccess;
  private final Queues queues = new Queues();
  private final MirrorSessionState state = new MirrorSessionState();
  private final QueueWatcher queueWatcher = new QueueWatcher(queues);
  private final SaveToLocal saveToLocal;
  private final FileWatcher fileWatcher;
  private final UpdateTree tree = UpdateTree.newRoot();
  private final SyncLogic syncLogic;
  private SaveToRemote saveToRemote;
  private SessionWatcher sessionWatcher;
  private StreamObserver<Update> outgoingChanges;

  public MirrorSession(Path root, FileSystem fileSystem) {
    this(//
      new ThreadBasedTaskFactory(),
      Clock.systemUTC(),
      root,
      new NativeFileAccess(root),
      new WatchServiceFileWatcher(new ThreadBasedTaskFactory(), newWatchService(fileSystem), root));
  }

  public MirrorSession(TaskFactory taskFactory, Clock clock, Path root, FileAccess fileAccess, FileWatcher fileWatcher) {
    this.taskFactory = taskFactory;
    this.clock = clock;
    this.fileAccess = fileAccess;
    this.fileWatcher = fileWatcher;

    syncLogic = new SyncLogic(queues, fileAccess, tree);
    // started in diffAndStartPolling

    saveToLocal = new SaveToLocal(queues, fileAccess);
    start(saveToLocal);

    start(queueWatcher);

    state.addStoppedCallback(() -> {
      taskFactory.stopTask(syncLogic);
      taskFactory.stopTask(saveToLocal);
      taskFactory.stopTask(fileWatcher); // may not have been started, but won't be null
      if (saveToRemote != null) {
        taskFactory.stopTask(saveToRemote);
      }
      if (queueWatcher != null) {
        taskFactory.stopTask(queueWatcher);
      }
      if (sessionWatcher != null) {
        taskFactory.stopTask(sessionWatcher);
      }
      if (outgoingChanges != null) {
        outgoingChanges.onCompleted();
      }
    });
  }

  public void addRemoteUpdate(Update update) {
    sessionWatcher.updateReceived(update);
    if (!update.getPath().equals(SessionWatcher.heartbeatPath)) {
      queues.incomingQueue.add(update);
    }
  }

  public void addStoppedCallback(Runnable r) {
    state.addStoppedCallback(r);
  }

  public List<Update> calcInitialState() throws IOException, InterruptedException {
    List<Update> initialUpdates = fileWatcher.performInitialScan(queues.incomingQueue);

    // We've drained the initial state, so we can tell FileWatcher to start polling now.
    // This will start filling up the queue, but not technically start processing/sending
    // updates to the remote (see #startPolling).
    start(fileWatcher);

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

    start(syncLogic);

    saveToRemote = new SaveToRemote(queues, fileAccess, outgoingChanges);
    start(saveToRemote);

    sessionWatcher = new SessionWatcher(clock, taskFactory, state, outgoingChanges);
    start(sessionWatcher);
  }

  public void stop() {
    log.info("Stopping session");
    state.stop();
  }

  private void start(TaskLogic task) {
    taskFactory.runTask(stopSessionOnFailure(state, task));
  }

  private static WatchService newWatchService(FileSystem fileSystem) {
    try {
      return fileSystem.newWatchService();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static TaskLogic stopSessionOnFailure(MirrorSessionState state, TaskLogic delegate) {
    return new TaskLogic() {
      @Override
      public Duration runOneLoop() throws InterruptedException {
        return delegate.runOneLoop();
      }

      @Override
      public void onStart() throws InterruptedException {
        delegate.onStart();
      }

      @Override
      public void onStop() throws InterruptedException {
        delegate.onStop();
      }

      @Override
      public void onFailure() throws InterruptedException {
        delegate.onFailure();
        state.stop();
      }

      @Override
      public String getName() {
        return delegate.getName();
      }
    };
  }
}

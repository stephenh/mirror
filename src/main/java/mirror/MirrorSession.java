package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import io.grpc.stub.StreamObserver;

public class MirrorSession {

  private final Path root;
  private final FileAccess fs = new NativeFileAccess();
  private final BlockingQueue<Update> queue = new ArrayBlockingQueue<>(1_000_000);
  private final FileWatcher watcher;

  public MirrorSession(Path root) {
    this.root = root;
    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      watcher = new FileWatcher(watchService, root, queue);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void enqueue(Update update) {
    queue.add(update);
  }

  public List<Update> calcInitialState() throws IOException, InterruptedException {
    List<Update> initial = watcher.performInitialScan();
    // we've drained the initial state, so we can tell FileWatcher to start polling now
    watcher.startPolling();
    return new InitialState(root, fs).prepare(initial);
  }

  public void setRemoteState(List<Update> state) {
  }

  public void startPolling(StreamObserver<Update> outgoingChanges) throws IOException, InterruptedException {
    SyncLogic s = new SyncLogic(root, queue, outgoingChanges, fs);
    s.startPolling();
  }
}

package mirror;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorStub;

public class MirrorClient {

  private final Path root;
  private final FileAccess fs = new NativeFileAccess();

  public MirrorClient(Path root) {
    this.root = root;
  }

  public void connect(MirrorStub stub) {
    BlockingQueue<Update> queue = new ArrayBlockingQueue<>(10_000);
    try {
      WatchService watchService = FileSystems.getDefault().newWatchService();
      FileWatcher r = new FileWatcher(watchService, root, queue);
      // throw away initial scan for now
      r.performInitialScan();
      List<Update> initial = new ArrayList<>();
      queue.drainTo(initial);
      r.startPolling();

      StreamObserver<Update> incomingChanges = new StreamObserver<Update>() {
        @Override
        public void onNext(Update update) {
          System.out.println("Received from server " + update);
          queue.add(update);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
      };
      StreamObserver<Update> outgoingChanges = stub.connect(incomingChanges);

      SyncLogic s = new SyncLogic(root, queue, outgoingChanges, fs);
      s.startPolling();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}

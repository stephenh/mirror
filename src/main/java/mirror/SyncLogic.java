package mirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;

/**
 * Implements the steady-state (post-initial sync) two-way sync logic.
 *
 * We poll for changes, either the remote host or our local disk, and
 * either persist it locally or send it out remotely, while also considering
 * whether we've since had a newer/conflicting change.
 */
public class SyncLogic {

  private static final String poisonPillPath = "SHUTDOWN NOW";
  private final Path rootDirectory;
  private final BlockingQueue<Update> changes;
  private final StreamObserver<Update> outgoing;
  private final FileAccess fileAccess;
  // eventually should be fancier
  private final Map<Path, Long> remoteState = new HashMap<Path, Long>();
  private volatile boolean shutdown = false;
  private final CountDownLatch isShutdown = new CountDownLatch(1);

  public SyncLogic(Path rootDirectory, BlockingQueue<Update> changes, StreamObserver<Update> outgoing, FileAccess fileAccess) {
    this.rootDirectory = rootDirectory;
    this.changes = changes;
    this.outgoing = outgoing;
    this.fileAccess = fileAccess;
  }

  /**
   * Starts polling for changes.
   *
   * Polling happens on a separate thread, so this method does not block.
   */
  public void startPolling() throws IOException, InterruptedException {
    Runnable runnable = () -> {
      try {
        pollLoop();
      } catch (Exception e) {
        // TODO need to signal that our connection needs reset
        throw new RuntimeException(e);
      }
    };
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("SyncLogic-%s").build().newThread(runnable).start();
  }

  public void stop() throws InterruptedException {
    shutdown = true;
    changes.add(Update.newBuilder().setPath(poisonPillPath).build());
    isShutdown.await();
  }

  private void pollLoop() throws IOException, InterruptedException {
    while (!shutdown) {
      Update u = changes.take();
      handleUpdate(u);
    }
    isShutdown.countDown();
  }

  @VisibleForTesting
  public void poll() throws IOException {
    Update u = changes.poll();
    if (u != null) {
      handleUpdate(u);
    }
  }

  private void handleUpdate(Update u) throws IOException {
    if (u.getPath().equals(poisonPillPath)) {
      return;
    }
    if (u.getLocal()) {
      handleLocal(u);
    } else {
      handleRemote(u);
    }
  }

  private void handleLocal(Update local) throws IOException {
    Path path = rootDirectory.resolve(local.getPath());
    if (!local.getDelete()) {
      // need to make a ByteString copy until GRPC supports ByteBuffers
      ByteString copy = ByteString.copyFrom(this.fileAccess.read(path));
      long localModTime = fileAccess.getModifiedTime(path);
      Long remoteModTime = remoteState.get(path);
      if (remoteModTime == null || remoteModTime.longValue() < localModTime) {
        Update toSend = Update.newBuilder(local).setData(copy).setModTime(localModTime).setLocal(false).build();
        outgoing.onNext(toSend);
      }
    } else {
      Long remoteModTime = remoteState.get(path);
      if (remoteModTime == null || remoteModTime != -1) {
        Update toSend = Update.newBuilder(local).setLocal(false).build();
        outgoing.onNext(toSend);
      }
    }
  }

  private void handleRemote(Update remote) throws IOException {
    Path path = rootDirectory.resolve(remote.getPath());
    if (!remote.getDelete()) {
      ByteBuffer data = remote.getData().asReadOnlyByteBuffer();
      fileAccess.write(path, data);
      fileAccess.setModifiedTime(path, remote.getModTime());
      // remember the last remote mod-time, so we don't echo back
      remoteState.put(path, remote.getModTime());
    } else {
      fileAccess.delete(path);
      // remember the last remote mod-time, so we don't echo back
      remoteState.put(path, -1L);
    }
  }

}

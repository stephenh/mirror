package mirror;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(SyncLogic.class);
  private static final String poisonPillPath = "SHUTDOWN NOW";
  private final BlockingQueue<Update> changes;
  private final StreamObserver<Update> outgoing;
  private final FileAccess fileAccess;
  private final PathState remoteState = new PathState();
  private volatile boolean shutdown = false;
  private final CountDownLatch isShutdown = new CountDownLatch(1);

  public SyncLogic(BlockingQueue<Update> changes, StreamObserver<Update> outgoing, FileAccess fileAccess) {
    this.changes = changes;
    this.outgoing = outgoing;
    this.fileAccess = fileAccess;
  }

  public void addRemoteState(PathState remoteState) {
    this.remoteState.add(remoteState);
  }

  /**
   * Starts polling for changes.
   *
   * Polling happens on a separate thread, so this method does not block.
   */
  public void startPolling() throws IOException {
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
    changes.clear();
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
    // TODO we should probably update our remoteState after each outgoing.onNext,
    // although right now it probably doesn't matter if the remoteState's timestamp
    // is stale, as any new handleLocal would be after any timestamp we set now anyway.
    Path path = Paths.get(local.getPath());
    if (!local.getSymlink().isEmpty()) {
      try {
        long localModTime = fileAccess.getModifiedTime(path);
        if (remoteState.needsUpdate(path, localModTime)) {
          Update toSend = Update.newBuilder(local).setModTime(localModTime).setLocal(false).build();
          outgoing.onNext(toSend);
        }
      } catch (FileNotFoundException fnfe) {
        log.info("Local symlink was not found, assuming deleted: " + fnfe.getMessage());
      }
    } else if (!local.getDelete()) {
      try {
        long localModTime = fileAccess.getModifiedTime(path);
        if (remoteState.needsUpdate(path, localModTime)) {
          // need to make a ByteString copy until GRPC supports ByteBuffers
          ByteString copy = ByteString.copyFrom(this.fileAccess.read(path));
          Update toSend = Update.newBuilder(local).setData(copy).setModTime(localModTime).setLocal(false).build();
          outgoing.onNext(toSend);
        }
      } catch (FileNotFoundException fnfe) {
        log.info("Local file was not found, assuming deleted: " + fnfe.getMessage());
      }
    } else { // a delete
      if (remoteState.needsDeleted(path)) {
        Update toSend = Update.newBuilder(local).setLocal(false).build();
        outgoing.onNext(toSend);
      }
    }
  }

  private void handleRemote(Update remote) throws IOException {
    Path path = Paths.get(remote.getPath());
    log.info("Received " + path);
    if (!remote.getSymlink().isEmpty()) {
      Path target = Paths.get(remote.getSymlink());
      fileAccess.createSymlink(path, target);
      fileAccess.setModifiedTime(path, remote.getModTime());
      // remember the last remote mod-time, so we don't echo back
      remoteState.record(path, remote.getModTime());
    } else if (!remote.getDelete()) {
      ByteBuffer data = remote.getData().asReadOnlyByteBuffer();
      fileAccess.write(path, data);
      fileAccess.setModifiedTime(path, remote.getModTime());
      // remember the last remote mod-time, so we don't echo back
      remoteState.record(path, remote.getModTime());
    } else {
      fileAccess.delete(path);
      // remember the last remote mod-time, so we don't echo back
      remoteState.record(path, -1L);
    }
  }

}

package mirror;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
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
      try {
        handleUpdate(u);
      } catch (Exception e) {
        log.error("Exception handling " + u.getPath(), e);
      }
    }
    isShutdown.countDown();
  }

  @VisibleForTesting
  public void poll() throws IOException, InterruptedException {
    Update u = changes.poll();
    if (u != null) {
      handleUpdate(u);
    }
  }

  private void handleUpdate(Update u) throws IOException, InterruptedException {
    if (u.getPath().equals(poisonPillPath)) {
      outgoing.onCompleted();
      return;
    }
    if (u.getPath().startsWith("STATUS:")) {
      log.info(u.getPath().replace("STATUS:", ""));
      return;
    }
    if (u.getLocal()) {
      handleLocal(u);
    } else {
      handleRemote(u);
    }
  }

  private void handleLocal(Update local) throws IOException, InterruptedException {
    if (!local.getSymlink().isEmpty()) {
      handleLocalSymlink(local);
    } else if (local.getDelete()) {
      handleLocalDelete(local);
    } else if (local.getDirectory()) {
      handleLocalDirectory(local);
    } else {
      handleLocalFile(local);
    }
  }

  private void handleLocalSymlink(Update local) throws IOException {
    Path path = Paths.get(local.getPath());
    try {
      long localModTime = fileAccess.getModifiedTime(path);
      if (remoteState.needsUpdate(path, localModTime)) {
        if (!local.getSeed()) {
          log.info("Local symlink {}", local.getPath());
        }
        String target = fileAccess.readSymlink(path).toString(); // in case it's changed
        Update toSend = Update.newBuilder(local).setModTime(localModTime).setSymlink(target).setLocal(false).build();
        outgoing.onNext(toSend);
        remoteState.record(path, localModTime);
      }
    } catch (FileNotFoundException | NoSuchFileException e) {
      log.info("Local symlink was not found, assuming deleted: " + path);
    }
  }

  private void handleLocalFile(Update local) throws IOException, InterruptedException {
    Path path = Paths.get(local.getPath());
    try {
      long localModTime = fileAccess.getModifiedTime(path);
      if (remoteState.needsUpdate(path, localModTime)) {
        if (!local.getSeed()) {
          log.info("Local update {}", local.getPath());
        }
        // do some gyrations to ensure the file writer has completely written the file
        boolean shouldBeComplete = false;
        while (!shouldBeComplete) {
          long size1 = fileAccess.getFileSize(path);
          if (fileWasJustModified(localModTime)) {
            Thread.sleep(100);
            localModTime = fileAccess.getModifiedTime(path);
            long size2 = fileAccess.getFileSize(path);
            shouldBeComplete = size1 == size2;
          } else {
            shouldBeComplete = true; // if seeded data, assume we don't need to sleep
          }
        }
        ByteString data = this.fileAccess.read(path);
        Update toSend = Update.newBuilder(local).setData(data).setModTime(localModTime).setLocal(false).build();
        outgoing.onNext(toSend);
        remoteState.record(path, localModTime);
      }
    } catch (FileNotFoundException | NoSuchFileException e) {
      log.info("Local file was not found, assuming deleted: " + path);
    }
  }

  private void handleLocalDirectory(Update local) throws IOException {
    Path path = Paths.get(local.getPath());
    if (remoteState.needsUpdate(path, local.getModTime())) {
      if (!local.getSeed()) {
        log.info("Local directory {}", local.getPath());
      }
      long localModTime = fileAccess.getModifiedTime(path);
      outgoing.onNext(Update.newBuilder(local).setLocal(false).setModTime(localModTime).build());
      remoteState.record(path, localModTime);
    }
  }

  private void handleLocalDelete(Update local) throws IOException {
    Path path = Paths.get(local.getPath());
    // ensure the file stayed deleted
    if (!fileAccess.exists(path) && remoteState.needsDeleted(path)) {
      if (!local.getSeed()) {
        log.info("Local delete {}", local.getPath());
      }
      Update toSend = Update.newBuilder(local).setLocal(false).build();
      outgoing.onNext(toSend);
      remoteState.record(path, -1L);
    }
  }

  private void handleRemote(Update remote) throws IOException {
    if (!remote.getSymlink().isEmpty()) {
      handleRemoteSymlink(remote);
    } else if (remote.getDelete()) {
      handleRemoteDelete(remote);
    } else if (remote.getDirectory()) {
      handleRemoteDirectory(remote);
    } else {
      handleRemoteFile(remote);
    }
  }

  private void handleRemoteDelete(Update remote) throws IOException {
    log.info("Remote delete {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    fileAccess.delete(path);
    // remember the last remote mod-time, so we don't echo back
    remoteState.record(path, -1L);
  }

  private void handleRemoteSymlink(Update remote) throws IOException {
    log.info("Remote symlink {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    Path target = Paths.get(remote.getSymlink());
    fileAccess.createSymlink(path, target);
    // this is going to trigger a local update, but since the write
    // doesn't go to the symlink, we think the symlink is changed
    fileAccess.setModifiedTime(path, remote.getModTime());
    // remember the last remote mod-time, so we don't echo back
    remoteState.record(path, remote.getModTime());
  }

  private void handleRemoteFile(Update remote) throws IOException {
    log.info("Remote update {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    ByteBuffer data = remote.getData().asReadOnlyByteBuffer();
    fileAccess.write(path, data);
    fileAccess.setModifiedTime(path, remote.getModTime());
    // remember the last remote mod-time, so we don't echo back
    remoteState.record(path, remote.getModTime());
  }

  private void handleRemoteDirectory(Update remote) throws IOException {
    log.info("Remote directory {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    fileAccess.mkdir(path);
    fileAccess.setModifiedTime(path, remote.getModTime());
    remoteState.record(path, remote.getModTime());
  }

  /** @return whether localModTime was within the last 100ms. */
  private static boolean fileWasJustModified(long localModTime) {
    return (System.currentTimeMillis() - localModTime) <= 100;
  }

}

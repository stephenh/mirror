package mirror;

import static mirror.Utils.debugString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;

import com.google.common.annotations.VisibleForTesting;

public class SaveToLocal extends AbstractThreaded {

  private final BlockingQueue<Update> results;
  private final FileAccess fileAccess;

  public SaveToLocal(MirrorSessionState state, Queues queues, FileAccess fileAccess) {
    super(state);
    this.results = queues.saveToLocal;
    this.fileAccess = fileAccess;
  }

  @Override
  protected void pollLoop() throws InterruptedException {
    while (!shutdown) {
      Update u = results.take();
      try {
        saveLocally(u);
      } catch (RuntimeException e) {
        log.error("Exception with results " + u, e);
      }
    }
  }

  @VisibleForTesting
  void drain() throws Exception {
    while (!results.isEmpty()) {
      saveLocally(results.take());
    }
  }

  private void saveLocally(Update remote) {
    try {
      if (remote.getDelete()) {
        deleteLocally(remote);
      } else if (!remote.getSymlink().isEmpty()) {
        saveSymlinkLocally(remote);
      } else if (remote.getDirectory()) {
        createDirectoryLocally(remote);
      } else {
        saveFileLocally(remote);
      }
    } catch (IOException e) {
      log.error("Error saving " + debugString(remote), e);
    }
  }

  // Note that this will generate a new local delete event (because we should
  // only be doing this when we want to immediately re-create the path as a
  // different type, e.g. a file -> a directory), but we end up ignoring
  // this stale delete event with isStaleLocalUpdate
  private void deleteLocally(Update remote) throws IOException {
    log.info("Deleting {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    fileAccess.delete(path);
  }

  private void saveSymlinkLocally(Update remote) throws IOException {
    log.info("Symlink {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    Path target = Paths.get(remote.getSymlink());
    fileAccess.createSymlink(path, target);
    // this is going to trigger a local update, but since the write
    // doesn't go to the symlink, we think the symlink is changed
    fileAccess.setModifiedTime(path, remote.getModTime());
  }

  private void createDirectoryLocally(Update remote) throws IOException {
    log.info("Directory {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    fileAccess.mkdir(path);
    fileAccess.setModifiedTime(path, remote.getModTime());
  }

  private void saveFileLocally(Update remote) throws IOException {
    log.info("Remote update {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    ByteBuffer data = remote.getData().asReadOnlyByteBuffer();
    fileAccess.write(path, data);
    fileAccess.setModifiedTime(path, remote.getModTime());
  }

}

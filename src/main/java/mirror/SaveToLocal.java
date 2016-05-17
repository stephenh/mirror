package mirror;

import static mirror.Utils.debugString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class SaveToLocal {

  private static final Logger log = LoggerFactory.getLogger(SaveToLocal.class);
  private final BlockingQueue<Update> results;
  private final FileAccess fileAccess;

  public SaveToLocal(Queues queues, FileAccess fileAccess) {
    this.results = queues.saveToLocal;
    this.fileAccess = fileAccess;
  }

  public void start() {
    Runnable runnable = () -> {
      try {
        pollLoop();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // TODO need to signal that our connection needs reset
        throw new RuntimeException(e);
      }
    };
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("SaveToLocal-%s").build().newThread(runnable).start();
  }

  public void stop() throws InterruptedException {
  }

  private void pollLoop() throws InterruptedException {
    while (true) {
      Update u = results.take();
      try {
        saveLocally(u);
      } catch (Exception e) {
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

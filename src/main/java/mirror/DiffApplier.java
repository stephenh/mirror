package mirror;

import static mirror.Utils.debugString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import mirror.UpdateTreeDiff.DiffResults;

/**
 * Applies the results from {@link UpdateTreeDiff} to both the local disk and to our remote peer.
 */
public class DiffApplier {

  private static final Logger log = LoggerFactory.getLogger(DiffApplier.class);

  // just for debugging/log messages, e.g. client/server 
  private final String role;
  private final StreamObserver<Update> outgoingChanges;
  private final FileAccess fileAccess;

  public DiffApplier(String role, StreamObserver<Update> outgoingChanges, FileAccess fileAccess) {
    this.role = role;
    this.outgoingChanges = outgoingChanges;
    this.fileAccess = fileAccess;
  }

  public void apply(DiffResults results) {
    results.saveLocally.forEach(this::saveLocally);
    results.sendToRemote.forEach(this::sendToRemote);
  }

  private void sendToRemote(Update update) {
    try {
      Update.Builder b = Update.newBuilder(update).setLocal(false);
      if (!update.getDirectory() && update.getSymlink().isEmpty() && !update.getDelete()) {
        b.setData(Utils.readDataFully(fileAccess, Paths.get(update.getPath())));
      }
      log.debug(role + " sending to remote " + update.getPath());
      outgoingChanges.onNext(b.build());
    } catch (InterruptedException e) {
      log.error(role + " interrupted " + debugString(update), e);
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.error(role + " could not read " + debugString(update), e);
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
      log.error(role + " error saving " + debugString(remote), e);
    }
  }

  // Note that this will generate a new local delete event (because we should
  // only be doing this when we want to immediately re-create the path as a
  // different type, e.g. a file -> a directory), but we end up ignoring
  // this stale delete event with isStaleLocalUpdate
  private void deleteLocally(Update remote) throws IOException {
    log.info(role + " deleting {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    fileAccess.delete(path);
  }

  private void saveSymlinkLocally(Update remote) throws IOException {
    log.info(role + " symlink {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    Path target = Paths.get(remote.getSymlink());
    fileAccess.createSymlink(path, target);
    // this is going to trigger a local update, but since the write
    // doesn't go to the symlink, we think the symlink is changed
    fileAccess.setModifiedTime(path, remote.getModTime());
  }

  private void createDirectoryLocally(Update remote) throws IOException {
    log.info(role + " directory {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    fileAccess.mkdir(path);
    fileAccess.setModifiedTime(path, remote.getModTime());
  }

  private void saveFileLocally(Update remote) throws IOException {
    log.info(role + " remote update {}", remote.getPath());
    Path path = Paths.get(remote.getPath());
    ByteBuffer data = remote.getData().asReadOnlyByteBuffer();
    fileAccess.write(path, data);
    fileAccess.setModifiedTime(path, remote.getModTime());
  }

}

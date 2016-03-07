package mirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;

/**
 * Implements the steady-state (post-initial sync) two-way sync logic.
 *
 * We poll for changes from either the remote host, or our local disk,
 * and then do the right thing.
 */
public class SyncLogic {

  private final Path rootDirectory;
  private final BlockingQueue<Update> changes;
  private final StreamObserver<Update> outgoing;
  private final FileAccess fileAccess;
  // eventually should be fancier
  private final Map<Path, Long> remoteState = new HashMap<Path, Long>();

  public SyncLogic(Path rootDirectory, BlockingQueue<Update> changes, StreamObserver<Update> outgoing, FileAccess fileAccess) {
    this.rootDirectory = rootDirectory;
    this.changes = changes;
    this.outgoing = outgoing;
    this.fileAccess = fileAccess;
  }

  @VisibleForTesting
  public void poll() throws IOException {
    Update u = changes.poll();
    if (u != null) {
      if (u.getLocal()) {
        handleLocal(u);
      } else {
        handleRemote(u);
      }
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

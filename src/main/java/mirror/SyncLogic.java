package mirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
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
  private final BlockingQueue<Update> remoteChanges;
  private final BlockingQueue<Update> localChanges;
  private final StreamObserver<Update> outgoing;
  private final FileAccess fileAccess;

  public SyncLogic(
    Path rootDirectory,
    BlockingQueue<Update> remoteChanges,
    BlockingQueue<Update> localChanges,
    StreamObserver<Update> outgoing,
    FileAccess fileAccess) {
    this.rootDirectory = rootDirectory;
    this.remoteChanges = remoteChanges;
    this.localChanges = localChanges;
    this.outgoing = outgoing;
    this.fileAccess = fileAccess;
  }

  @VisibleForTesting
  public void pollLocal() throws IOException {
    Update local = this.localChanges.poll();
    if (local != null) {
      Path path = rootDirectory.resolve(local.getPath());
      if (!local.getDelete()) {
        // need to make a ByteString copy until GRPC supports ByteBuffers
        ByteString copy = ByteString.copyFrom(this.fileAccess.read(path));
        long time = fileAccess.getModifiedTime(path);
        Update toSend = Update.newBuilder(local).setData(copy).setModTime(time).build();
        outgoing.onNext(toSend);
      } else {
        outgoing.onNext(local);
      }
    }
  }

  @VisibleForTesting
  public void pollRemote() throws IOException {
    Update remote = this.remoteChanges.poll();
    if (remote != null) {
      Path path = rootDirectory.resolve(remote.getPath());
      if (!remote.getDelete()) {
        ByteBuffer data = remote.getData().asReadOnlyByteBuffer();
        fileAccess.write(path, data);
      } else {
        fileAccess.delete(path);
      }
    }
  }

}

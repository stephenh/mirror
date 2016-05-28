package mirror;

import java.util.ArrayList;
import java.util.List;

import com.google.protobuf.Descriptors.FieldDescriptor;

import mirror.UpdateTree.Node;

/**
 * Diffs two trees to bring the local/remote directories into sync.
 *
 * For each node, the possible combinations are:
 * - file/file, local/remote is newer
 * - dir/dir, remote/local is newer
 * - symlink/symlink, local/remote is newer
 * 
 * - dir/file, local/remote is newer
 * - dir/symlink, local/remote is newer
 * - symlink/file, local/remote is newer
 * 
 * - missing/file
 * - file/missing
 * - symlink/missing
 * - missing/symlink
 * - dir/missing
 * - missing/dir
 * 
 * Although there are a lot of cases, there are four possible outcomes:
 * 
 * - send our local file/dir/symlink to the remote peer
 * - save the remote file/dir/symlink to our local copy 
 * - do nothing because they are in sync
 * - do nothing because the remote hasn't sent their copy yet
 */
public class UpdateTreeDiff {

  // private static final Logger log = LoggerFactory.getLogger(UpdateTreeDiff.class);
  private static final FieldDescriptor updateDataField = Update.getDescriptor().findFieldByNumber(Update.DATA_FIELD_NUMBER);

  public static class DiffResults {
    public final List<Update> sendToRemote = new ArrayList<>();
    public final List<Update> saveLocally = new ArrayList<>();

    @Override
    public String toString() {
      return "[sendToRemote=" + sendToRemote.size() + ",saveLocally=" + saveLocally.size() + "]";
    }
  }

  private final UpdateTree tree;

  public UpdateTreeDiff(UpdateTree tree) {
    this.tree = tree;
  }

  public DiffResults diff() {
    DiffResults results = new DiffResults();
    // Utils.time(log, "diff", () -> tree.visit(node -> diff(results, node)));
    tree.visitDirty(node -> diff(results, node));
    return results;
  }

  private void diff(DiffResults results, Node node) {
    Update local = node.getLocal();
    Update remote = node.getRemote();

    if (node.isLocalNewer()) {
      if (!node.shouldIgnore()) {
        results.sendToRemote.add(local);
      }
      node.setRemote(local);
    } else if (node.isRemoteNewer()) {
      // if we were a directory, and this is now a file, do an explicit delete first
      if (local != null && !node.isSameType() && !local.getDelete()) {
        Update delete = Update.newBuilder(local).setDelete(true).build();
        results.saveLocally.add(delete);
        node.setLocal(delete);
      }
      // during the initial sync, we don't have any remote data in the UpdateTree (only metadata is sent),
      // so we can't save the data locally, and instead soon-ish we should be sent data-filled Updates by
      // the remote when it does it's own initial sync
      boolean skipBecauseNoData = node.isFile(remote) && !remote.getDelete() && !remote.hasField(updateDataField);
      if (!skipBecauseNoData) {
        if (!node.shouldIgnore()) {
          results.saveLocally.add(remote);
        }
        // we're done with the data, so don't keep it in memory
        node.clearData();
        node.setLocal(remote);
      }
    }
  }

}

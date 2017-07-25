package mirror;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(UpdateTree.class);

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
        debugIfEnabled(node, "isLocalNewer");
        results.sendToRemote.add(node.restorePath(local));
      }
      node.setRemote(local);
    } else if (node.isRemoteNewer()) {
      // if we were a directory, and this is now a file, do an explicit delete first
      if (local != null && !node.isSameType() && !local.getDelete() && !remote.getDelete()) {
        Update delete = local.toBuilder().setDelete(true).build();
        results.saveLocally.add(node.restorePath(delete));
        node.setLocal(delete);
      }
      // during the initial sync, we don't have any remote data in the UpdateTree (only metadata is sent),
      // so we can't save the data locally, and instead soon-ish we should be sent data-filled Updates by
      // the remote when it does it's own initial sync
      boolean skipBecauseNoData = UpdateTree.isFile(remote) && !remote.getDelete() && remote.getData().equals(UpdateTree.initialSyncMarker);
      if (!skipBecauseNoData) {
        if (!node.shouldIgnore()) {
          debugIfEnabled(node, "isRemoteNewer");
          results.saveLocally.add(node.restorePath(remote));
        }
        // we're done with the data, so don't keep it in memory
        node.clearData();
        node.setLocal(remote);
      }
    } else {
      // should rarely/never happen (although it did happen when a bug existed), but
      // if the remote side sends over data that exactly matches what we already have,
      // we won't save but, which is fine, but make sure we free it from memory
      node.clearData();
    }
  }

  private void debugIfEnabled(Node node, String operation) {
    if (tree.shouldDebug(node)) {
      log.info(node.getPath() + " " + operation);
      log.info("  l: " + UpdateTree.toDebugString(node.getLocal()));
      log.info("  r: " + UpdateTree.toDebugString(node.getRemote()));
    }
  }

}

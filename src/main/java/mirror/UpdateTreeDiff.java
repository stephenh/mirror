package mirror;

import static java.util.Optional.ofNullable;
import static org.jooq.lambda.Seq.seq;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import org.jooq.lambda.Seq;

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

  private static final FieldDescriptor updateDataField = Update.getDescriptor().findFieldByNumber(Update.DATA_FIELD_NUMBER);

  public static class DiffResults {
    public final List<Update> sendToRemote = new ArrayList<>();
    public final List<Update> saveLocally = new ArrayList<>();
  }

  private final UpdateTree localTree;
  private final UpdateTree remoteTree;

  public UpdateTreeDiff(UpdateTree localTree, UpdateTree remoteTree) {
    this.localTree = localTree;
    this.remoteTree = remoteTree;
  }

  public DiffResults diff() {
    DiffResults results = new DiffResults();
    Queue<Visit> queue = new LinkedBlockingQueue<>();
    queue.add(new Visit(Optional.of(localTree.root), Optional.of(remoteTree.root)));
    while (!queue.isEmpty()) {
      diff(results, queue, queue.remove());
    }
    return results;
  }

  private void diff(DiffResults results, Queue<Visit> queue, Visit visit) {
    Node local = visit.local.orElse(null);
    Node remote = visit.remote.orElse(null);

    if (local != null && local.isNewer(remote)) {
      if (!local.shouldIgnore()) {
        results.sendToRemote.add(local.getUpdate());
      }
      remoteTree.add(local.getUpdate());
      // might eventually be cute to do:
      // remote = remoteTree.add(local.getUpdate());
    } else if (remote != null && remote.isNewer(local)) {
      // if we were a directory, and this is now a file, do an explicit delete first
      if (local != null && !local.isSameType(remote) && !local.getUpdate().getDelete()) {
        Update delete = Update.newBuilder(local.getUpdate()).setDelete(true).build();
        results.saveLocally.add(delete);
        localTree.add(delete);
        local = null;
      }
      // during the initial sync, we don't have any remote data in the UpdateTree (only metadata is sent),
      // so we can't save the data locally, and instead soon-ish we should be sent data-filled Updates by
      // the remote when it does it's own initial sync
      boolean skipBecauseNoData = remote.isFile() && !remote.getUpdate().getDelete() && !remote.getUpdate().hasField(updateDataField);
      if (!skipBecauseNoData) {
        if (!remote.shouldIgnore()) {
          results.saveLocally.add(remote.getUpdate());
        }
        // we're done with the data, so don't keep it in memory
        remote.clearData();
        localTree.add(remote.getUpdate());
      }
    }

    // ensure local/remote ignore data is synced first
    ensureGitIgnoreIsSynced(local, remote);

    // we recurse into sub directories, even if this current directory
    // is .gitignored, so that we can search for custom included files.
    for (String childName : Seq
      .of(visit.local, visit.remote)
      .flatMap(o -> seq(o)) // flatten
      .flatMap(node -> seq(node.getChildren()))
      .map(child -> child.getName())
      .distinct()) {
      Optional<Node> localChild = ofNullable(local).flatMap(n -> n.getChild(childName));
      Optional<Node> remoteChild = ofNullable(remote).flatMap(n -> n.getChild(childName));
      queue.add(new Visit(localChild, remoteChild));
    }
  }

  private void ensureGitIgnoreIsSynced(Node local, Node remote) {
    if (local != null && local.isDirectory() && remote != null && remote.isDirectory()) {
      Optional<Node> localIgnore = seq(local.getChildren()).findFirst(n -> n.getName().equals(".gitignore"));
      Optional<Node> remoteIgnore = seq(remote.getChildren()).findFirst(n -> n.getName().equals(".gitignore"));
      if (remoteIgnore.isPresent() && localIgnore.isPresent() && remoteIgnore.get().isNewer(localIgnore.get())) {
        local.setIgnoreRules(remoteIgnore.get().getIgnoreString());
      } else if (remoteIgnore.isPresent() && !localIgnore.isPresent()) {
        local.setIgnoreRules(remoteIgnore.get().getIgnoreString());
      }
    }
  }

  /**
   * A combination of the matching local/remote node in each tree.
   *
   * E.g. if remote has foo.txt and bar.txt, and local only has foo.txt,
   * there would be one Visit with remote=Some(foo.txt),local=Some(foo.txt),
   * and another Visit with remote=Some(bar.txt),local=None.
   */
  private static class Visit {
    private final Optional<Node> local;
    private final Optional<Node> remote;

    private Visit(Optional<Node> local, Optional<Node> remote) {
      this.local = local;
      this.remote = remote;
    }

    @Override
    public String toString() {
      return local + "/" + remote;
    }
  }

}

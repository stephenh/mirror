package mirror;

import static java.util.Optional.ofNullable;
import static org.jooq.lambda.Seq.seq;

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

  interface DiffResults {
    void sendToRemote(Update update);

    void saveLocally(Update update);
  }

  private final UpdateTree localTree;
  private final UpdateTree remoteTree;
  private final DiffResults results;

  public UpdateTreeDiff(UpdateTree localTree, UpdateTree remoteTree, DiffResults results) {
    this.localTree = localTree;
    this.remoteTree = remoteTree;
    this.results = results;
  }

  public void diff() {
    Queue<Visit> queue = new LinkedBlockingQueue<>();
    queue.add(new Visit(Optional.of(localTree.root), Optional.of(remoteTree.root)));
    while (!queue.isEmpty()) {
      diff(queue, queue.remove());
    }
  }

  private void diff(Queue<Visit> queue, Visit visit) {
    Node local = visit.local.orElse(null);
    Node remote = visit.remote.orElse(null);

    if (local != null && local.isNewer(remote)) {
      if (!local.shouldIgnore()) {
        results.sendToRemote(local.getUpdate());
      }
      remoteTree.add(local.getUpdate());
    } else if (remote != null && remote.isNewer(local)) {
      // if we were a directory, and this is now a file, do an explicit delete first
      if (local != null && !local.isSameType(remote) && !local.getUpdate().getDelete()) {
        Update delete = Update.newBuilder(local.getUpdate()).setDelete(true).build();
        results.saveLocally(delete);
        localTree.add(delete);
        local = null;
      }
      // during the initialSync, we don't have any data in the UpdateTree (only metadata is sent),
      // so we can't save the data locally, and instead will get be sent data-filled Updates by
      // the remote when it does it's own initialSync
      if (!remote.isFile() || remote.getUpdate().getDelete() || remote.getUpdate().hasField(updateDataField)) {
        if (!remote.shouldIgnore()) {
          results.saveLocally(remote.getUpdate());
        }
        // we're done with the data, so don't keep it in memory
        remote.clearData();
        localTree.add(remote.getUpdate());
      }
    }

    if (local != null && local.isDirectory()) {
      // ensure local/remote ignore data is synced first
      if (remote != null && remote.isDirectory()) {
        Optional<Node> localIgnore = seq(local.getChildren()).findFirst(n -> n.getName().equals(".gitignore"));
        Optional<Node> remoteIgnore = seq(remote.getChildren()).findFirst(n -> n.getName().equals(".gitignore"));
        if (remoteIgnore.isPresent() && localIgnore.isPresent() && remoteIgnore.get().isNewer(localIgnore.get())) {
          local.setIgnoreRules(remoteIgnore.get().getIgnoreString());
        } else if (remoteIgnore.isPresent() && !localIgnore.isPresent()) {
          local.setIgnoreRules(remoteIgnore.get().getIgnoreString());
        }
      }
    }

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

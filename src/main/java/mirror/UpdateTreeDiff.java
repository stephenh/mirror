package mirror;

import static java.util.Optional.ofNullable;
import static org.jooq.lambda.Seq.seq;
import static org.jooq.lambda.tuple.Tuple.tuple;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import org.jooq.lambda.tuple.Tuple2;

import mirror.UpdateTree.Node;

/**
 * Diffs two trees to bring the local/remote directories into sync.
 *
 * For each node:
 * - file/file, local/remote is newer
 * - dir/dir, remote/local is newer
 * - symlink/symlink, local/remote is newer
 * 
 * - dir/file, local/remote is newer
 * - dir/symlink, local/remote is newer
 * - symlink/file, local/remote is newer
 * 
 * - null/file
 * - file/null
 * - symlink/null
 * - null/symlink
 * - dir/null
 * - null/dir
 * 
 * The total possible outcomes are:
 * - send our file/dir/symlink to remote
 * - wait for update file/dir/symlink to be sent from remote
 * - move our file/dir/symlink because it's going to be replaced
 */
public class UpdateTreeDiff {

  interface TreeResults {
    void sendToRemote(Node node);

    void deleteLocally(Node node);
  }

  private TreeResults results;

  public UpdateTreeDiff(TreeResults results) {
    this.results = results;
  }

  public void diff(UpdateTree local, UpdateTree remote) {
    Queue<Tuple2<Node, Node>> queue = new LinkedBlockingQueue<>();
    queue.add(tuple(local.root, remote.root));
    while (!queue.isEmpty()) {
      Tuple2<Node, Node> pair = queue.remove();
      diff(queue, pair.v1, pair.v2);
    }
  }
  
  private void diff(Queue<Tuple2<Node, Node>> queue, Node local, Node remote) {
    if (local.isNewer(remote)) {
      results.sendToRemote(local);
    } else if (!local.isSameType(remote)) {
      results.deleteLocally(local);
    }
    if (local.isDirectory()) {
      // don't recurse if we've decided our directory is stale
      if (local.isNewer(remote) || local.isSameType(remote)) {
        for (Node localChild : local.getChildren()) {
          Optional<Node> remoteChild = seq(ofNullable(remote))
            .flatMap(n -> seq(n.getChildren()))
            .findFirst(n -> n.getName().equals(localChild.getName()));
          queue.add(tuple(localChild, remoteChild.orElse(null)));
        }
      }
    }
  }

  /*
  if (local.isFile()) {
    if (remote == null) {
      // file does not exist on remote
      results.sendToRemote(local);
    } else if (remote.isFile()) {
      // both are files, just see if we're newer
      sendToRemoteIfNewer(local, remote);
    } else if (remote.isDirectory() || remote.isSymlink()) {
      sendToRemoteIfNewerOrDeleteLocally(local, remote);
    } else {
      throw new IllegalStateException("Unhandled remote type " + remote);
    }
  } else if (local.isDirectory()) {
    if (remote == null) {
      // directory does not exist on remote
      results.sendToRemote(local);
      for (Node localChild : local.getChildren()) {
        queue.add(tuple(localChild, null));
      }
    } else if (remote.isDirectory()) {
      // both are directories, so just recurse
      for (Node localChild : local.getChildren()) {
        Optional<Node> remoteChild = Seq.seq(remote.getChildren()).findFirst(n -> n.getName().equals(localChild.getName()));
        queue.add(tuple(localChild, remoteChild.orElse(null)));
      }
    } else if (remote.isFile() || remote.isSymlink()) {
      sendToRemoteIfNewerOrDeleteLocally(local, remote);
    } else {
      throw new IllegalStateException("Unhandled remote type " + remote);
    }
  } else if (local.isSymlink()) {
    if (remote == null) {
      // symlink does not exist on remote
      results.sendToRemote(local);
    } else if (remote.isSymlink()) {
      sendToRemoteIfNewer(local, remote);
    } else if (remote.isFile() || remote.isDirectory()) {
      sendToRemoteIfNewerOrDeleteLocally(local, remote);
    } else {
      throw new IllegalStateException("Unhandled remote type " + remote);
    }
  } else {
    throw new IllegalStateException("Unhandled local type " + local);
  }
  */

}

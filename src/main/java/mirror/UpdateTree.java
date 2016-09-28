package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jooq.lambda.Seq;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

/**
 * A tree of file+directory metadata ({@link Update}s).
 *
 * Given comparing remote/local data is our main task, we store
 * both remote+local metadata within the same tree instance,
 * e.g. each node contains both it's respective remote+local Updates.
 *
 * All of the {@link Update}s within the UpdateTree should contain
 * metadata only, and as the tree is solely for tracking/diffing
 * the state of the remote vs. local directories.
 *
 * This class is not thread safe as it's assumed to be fed Updates
 * from a dedicated queue/thread, e.g. in {@link SyncLogic}.
 */
public class UpdateTree {

  public static final ByteString initialSyncMarker = ByteString.copyFrom("initialSyncMarker", Charsets.UTF_8);
  private final Node root;
  private final PathRules extraIncludes;
  private final PathRules extraExcludes;

  public static NodeType getType(Update u) {
    return u == null ? null : isDirectory(u) ? NodeType.Directory : isSymlink(u) ? NodeType.Symlink : NodeType.File;
  }

  public static boolean isDirectory(Update u) {
    return u.getDirectory();
  }

  public static boolean isFile(Update u) {
    return !isDirectory(u) && !isSymlink(u);
  }

  public static boolean isSymlink(Update u) {
    return !u.getSymlink().isEmpty();
  }

  public static UpdateTree newRoot() {
    return newRoot(new PathRules(), new PathRules());
  }

  public static UpdateTree newRoot(PathRules extraIncludes, PathRules extraExcludes) {
    return new UpdateTree(extraIncludes, extraExcludes);
  }

  private UpdateTree(PathRules extraIncludes, PathRules extraExcludes) {
    this.extraIncludes = extraIncludes;
    this.extraExcludes = extraExcludes;
    this.root = new Node(null, "");
    this.root.setLocal(Update.newBuilder().setPath("").setDirectory(true).build());
    this.root.setRemote(Update.newBuilder().setPath("").setDirectory(true).build());
  }

  /**
   * Adds {@code update} to our tree of nodes. 
   *
   * We assume the updates come in a certain order, e.g. foo/bar.txt should have
   * it's directory foo added first.
   */
  public void addLocal(Update local) {
    addUpdate(local, true);
  }

  public void addRemote(Update remote) {
    addUpdate(remote, false);
  }

  private void addUpdate(Update update, boolean local) {
    if (update.getPath().startsWith("/") || update.getPath().endsWith("/")) {
      throw new IllegalArgumentException("Update path should not start or end with slash: " + update.getPath());
    }
    Node node = find(update.getPath());
    if (local) {
      node.setLocal(update);
    } else {
      node.setRemote(update);
    }
  }

  /** Invokes {@link visitor} at each node in the tree, including the root. */
  public void visit(Consumer<Node> visitor) {
    visit(root, n -> {
      visitor.accept(n);
      return true;
    });
  }

  /**
   * Invokes {@link visitor} at each dirty node in the tree, including the root.
   *
   * After this method completes, all nodes are reset to clean. 
   */
  public void visitDirty(Consumer<Node> visitor) {
    visit(root, n -> {
      if (n.isDirty) {
        visitor.accept(n);
        n.isDirty = false;
      }
      boolean cont = n.hasDirtyDecendent;
      n.hasDirtyDecendent = false;
      return cont;
    });
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    visit(node -> sb.append(node.getPath() //
      + " local="
      + node.local.getModTime()
      + " remote="
      + node.remote.getModTime()).append("\n"));
    return sb.toString();
  }

  @VisibleForTesting
  Node find(String path) {
    if ("".equals(path)) {
      return root;
    }
    // breaks up "foo/bar/zaz.txt", into [foo, bar, zaz.txt]
    List<Path> parts = Lists.newArrayList(Paths.get(path));
    // find parent directory
    Node current = root;
    for (Path part : parts) {
      current = current.getChild(part.getFileName().toString());
    }
    return current;
  }

  @VisibleForTesting
  List<Node> getChildren() {
    return root.children;
  }

  public enum NodeType {
    File, Directory, Symlink
  };

  /** Either a directory or file within the tree. */
  public class Node {
    private final Node parent;
    private final String name;
    private final List<Node> children = new ArrayList<>();
    // should contain .gitignore + svn:ignore + custom excludes/includes
    private final PathRules ignoreRules = new PathRules();
    private boolean hasDirtyDecendent;
    private boolean isDirty;
    private Update local;
    private Update remote;
    private Boolean shouldIgnore;

    private Node(Node parent, String name) {
      this.parent = parent;
      this.name = name;
    }

    boolean isSameType() {
      return getType(local) == getType(remote);
    }

    Update getRemote() {
      return remote;
    }

    void setRemote(Update remote) {
      this.remote = clearPath(remote);
      updateParentIgnoreRulesIfNeeded();
      markDirty();
    }

    Update getLocal() {
      return local;
    }

    void setLocal(Update local) {
      // The best we can do for guessing the mod time of deletions
      // is to take the old, known mod time and just tick 1
      if (local != null && this.local != null && local.getDelete() && local.getModTime() == 0L) {
        int tick = this.local.getDelete() ? 0 : 1;
        local = Update.newBuilder(local).setModTime(this.local.getModTime() + tick).build();
      }
      this.local = clearPath(local);
      // If we're no longer a directory, or we got deleted, clear our children
      if (!UpdateTree.isDirectory(local) || local.getDelete()) {
        children.clear();
      }
      updateParentIgnoreRulesIfNeeded();
      markDirty();
    }

    /** Clear the path data so we don't have it in RAM. */
    Update clearPath(Update u) {
      return Update.newBuilder(u).clearPath().build();
    }

    /** Set the path back for sending to remote or file system. */
    Update setPath(Update u) {
      return Update.newBuilder(u).setPath(getPath()).build();
    }

    boolean isRemoteNewer() {
      return isNewer(remote, local);
    }

    boolean isLocalNewer() {
      return isNewer(local, remote);
    }

    boolean isNewer(Update a, Update b) {
      return a != null
        && (b == null || a.getModTime() > b.getModTime())
        && !(a.getDelete() && (b == null || b.getDelete())) // ignore no-op deletes
        && !(!a.getDelete() && UpdateTree.isDirectory(a) && b != null && UpdateTree.isDirectory(b)); // modtimes on existing dirs don't matter
    }

    String getName() {
      return name;
    }

    String getPath() {
      StringBuilder sb = new StringBuilder();
      Node current = parent;
      while (current != null && current != root) {
        sb.insert(0, "/");
        sb.insert(0, current.name);
        current = current.parent;
      }
      sb.append(name);
      return sb.toString();
    }

    /** @return the node for {@code name}, and will create it if necessary */
    Node getChild(String name) {
      for (Node child : children) {
        if (child.getName().equals(name)) {
          return child;
        }
      }
      Node child = new Node(this, name);
      children.add(child);
      return child;
    }

    List<Node> getChildren() {
      return children;
    }

    void clearData() {
      remote = Update.newBuilder(remote).setData(ByteString.EMPTY).build();
    }

    boolean isDirectory() {
      return local != null ? UpdateTree.isDirectory(local) : remote != null ? UpdateTree.isDirectory(remote) : false;
    }

    /** @param p should be a relative path, e.g. a/b/c.txt. */
    boolean shouldIgnore() {
      if (shouldIgnore != null) {
        return shouldIgnore;
      }
      // temporarily calc our path
      String path = getPath();
      boolean gitIgnored = parents().anyMatch(node -> {
        if (node.shouldIgnore()) {
          return true;
        } else if (node.ignoreRules.hasAnyRules()) {
          // if our path is dir1/dir2/foo.txt, strip off dir1/ for dir1's .gitignore, so we pass dir2/foo.txt
          String relative = path.substring(node.getPath().length());
          return node.ignoreRules.matches(relative, isDirectory());
        } else {
          return false;
        }
      });
      // besides parent .gitignores, also use our extra includes/excludes
      boolean extraIncluded = extraIncludes.matches(path, isDirectory());
      boolean extraExcluded = extraExcludes.matches(path, isDirectory());
      shouldIgnore = (gitIgnored || extraExcluded) && !extraIncluded;
      return shouldIgnore;
    }

    void updateParentIgnoreRulesIfNeeded() {
      if (!".gitignore".equals(name)) {
        return;
      }
      if (isLocalNewer()) {
        parent.setIgnoreRules(local.getIgnoreString());
      } else if (isRemoteNewer()) {
        parent.setIgnoreRules(remote.getIgnoreString());
      }
    }

    void markDirty() {
      isDirty = true;
      parents().forEach(n -> n.hasDirtyDecendent = true);
    }

    void setIgnoreRules(String ignoreData) {
      ignoreRules.setRules(ignoreData);
      visit(this, n -> {
        n.shouldIgnore = null;
        return true;
      });
    }

    @Override
    public String toString() {
      return name;
    }

    private Seq<Node> parents() {
      return Seq.iterate(parent, t -> t.parent).limitUntil(Objects::isNull);
    }
  }

  /** Visits nodes in the tree, in breadth-first order, continuing if {@visitor} returns true. */
  private static void visit(Node start, Predicate<Node> visitor) {
    Queue<Node> queue = new LinkedBlockingQueue<Node>();
    queue.add(start);
    while (!queue.isEmpty()) {
      Node node = queue.remove();
      boolean cont = visitor.test(node);
      if (cont) {
        queue.addAll(node.children);
      }
    }
  }

}

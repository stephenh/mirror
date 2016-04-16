package mirror;

import static org.jooq.lambda.Seq.seq;
import static org.jooq.lambda.tuple.Tuple.tuple;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple2;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

/**
 * A tree of updates, e.g. both files and directories.
 *
 * All of the {@link Update}s within the UpdateTree should contain
 * metadata only, and as the tree is solely for tracking/diffing
 * the state of the remote vs. local directories.
 *
 * This class is not thread safe as it's assumed to be fed Updates
 * from a dedicated queue/thread, e.g. in {@link SyncLogic}.
 */
public class UpdateTree {

  final Node root;
  final PathRules extraIncludes = new PathRules();
  final PathRules extraExcludes = new PathRules();

  public static UpdateTree newRoot() {
    return new UpdateTree();
  }

  private UpdateTree() {
    this.root = new Node(null, Update.newBuilder().setPath("").setDirectory(true).build());
    // IntegrationTest currently depends on these values
    extraExcludes.setRules("tmp", "temp", "target", "build", "bin", ".*");
    extraIncludes.setRules("src_managed", "*-SNAPSHOT.jar", ".classpath", ".project", ".gitignore");
  }

  public void addAll(List<Update> updates) {
    updates.forEach(this::add);
  }

  /**
   * Adds {@code update} to our tree of nodes. 
   *
   * We assume the updates come in a certain order, e.g. foo/bar.txt should have
   * it's directory foo added first.
   */
  public void add(Update update) {
    if (update.getPath().startsWith("/") || update.getPath().endsWith("/")) {
      throw new IllegalArgumentException("Update path should not start or end with slash: " + update.getPath());
    }
    Tuple2<Node, Optional<Node>> t = find(update.getPath());
    Node parent = t.v1;
    Optional<Node> existing = t.v2;
    if (existing.isPresent()) {
      existing.get().setUpdate(update);
    } else {
      parent.addChild(new Node(parent, update));
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    visit(node -> sb.append(node.getPath() //
      + " mod="
      + node.getModTime()
      + " deleted="
      + node.getUpdate().getDelete()).append("\n"));
    return sb.toString();
  }

  /** Visits each node in the tree, in breadth-first order. */
  private void visit(Consumer<Node> visitor) {
    Queue<Node> queue = new LinkedBlockingQueue<Node>();
    queue.add(root);
    while (!queue.isEmpty()) {
      Node node = queue.remove();
      visitor.accept(node);
      queue.addAll(node.children);
    }
  }

  /** @return a tuple of the path's parent directory and the existing node (if found) */
  private Tuple2<Node, Optional<Node>> find(String path) {
    if ("".equals(path)) {
      return tuple(null, Optional.of(root));
    }
    // breaks up "foo/bar/zaz.txt", into [foo, bar, zaz.txt]
    List<Path> parts = Lists.newArrayList(Paths.get(path));
    // find parent directory
    int i = 0;
    Node current = root;
    for (; i < parts.size() - 1; i++) {
      String name = parts.get(i).getFileName().toString();
      // lambdas need final variables
      final Node fc = current;
      final int fi = i;
      current = seq(current.children).findFirst(t -> t.name.equals(name)).orElseGet(() -> {
        String dir = Joiner.on("/").join(parts.subList(0, fi + 1));
        Node child = new Node(fc, Update.newBuilder().setPath(dir).setModTime(0).build());
        fc.addChild(child);
        return child;
      });
    }
    // now handle the last part which is the file name
    String name = parts.get(i).getFileName().toString();
    Optional<Node> existing = seq(current.children).findFirst(t -> t.name.equals(name));
    return tuple(current, existing);
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
    // State == dirty, synced, partial-ignore/full-ignore?
    // we keep a copy of the modTime so that SyncLogic can mutate it
    // to mark that we've sent paths back to the remote.
    private Update update;

    private Node(Node parent, Update update) {
      this.parent = parent;
      this.update = update;
      name = Paths.get(update.getPath()).getFileName().toString();
    }

    boolean isSameType(Node o) {
      return getType() == o.getType();
    }

    private NodeType getType() {
      return isDirectory() ? NodeType.Directory : isSymlink() ? NodeType.Symlink : NodeType.File;
    }

    Update getUpdate() {
      return update;
    }

    void addChild(Node child) {
      children.add(child);
      if (child.getName().equals(".gitignore")) {
        setIgnoreRules(child.getUpdate().getIgnoreString());
      }
    }

    void setUpdate(Update update) {
      if (!this.update.getPath().equals(update.getPath())) {
        throw new IllegalStateException("Update path for a node should not change: " + this.update.getPath() + " vs. " + update.getPath());
      }
      // The best we can do for guessing the mod time of deletions
      // is to take the old, known mod time and just tick 1
      if (update.getDelete()) {
        int tick = this.update.getDelete() ? 0 : 1;
        update = Update.newBuilder(update).setModTime(getModTime() + tick).build();
      }
      // TODO update parent directory's ignore rules if this is a .gitignore file
      this.update = update;
      // If we're no longer a directory, or we got deleted, clear our children
      if (!isDirectory() || update.getDelete()) {
        children.clear();
      }
    }

    boolean isNewer(Node o) {
      boolean newer = o == null || getModTime() > o.getModTime();
      return newer;
    }

    String getName() {
      return name;
    }

    String getPath() {
      return update.getPath();
    }

    Optional<Node> getChild(String name) {
      return seq(children).findFirst(c -> c.getName().equals(name));
    }

    List<Node> getChildren() {
      return children;
    }

    long getModTime() {
      return update.getModTime();
    }

    void clearData() {
      this.update = Update.newBuilder(update).setData(ByteString.EMPTY).build();
    }

    boolean isFile() {
      return !isDirectory() && !isSymlink();
    }

    boolean isDirectory() {
      return update.getDirectory();
    }

    boolean isSymlink() {
      return !update.getSymlink().isEmpty();
    }

    String getIgnoreString() {
      return update.getIgnoreString();
    }

    /** @param p should be a relative path, e.g. a/b/c.txt. */
    public boolean shouldIgnore() {
      String path = update.getPath();
      boolean gitIgnored = Seq.iterate(parent, t -> t.parent).limitUntil(Objects::isNull).reverse().findFirst(n -> {
        // e.g. directory might be dir1/dir2, and p is dir1/dir2/foo.txt, we want
        // to call is match with just foo.txt, and not the dir1/dir2 prefix
        String relative = path.substring(n.update.getPath().length()).replaceAll("^/", "");
        return n.ignoreRules.hasMatchingRule(relative, this.isDirectory());
      }).isPresent();
      boolean extraIncluded = extraIncludes.hasMatchingRule(path, isDirectory());
      boolean extraExcluded = extraExcludes.hasMatchingRule(path, isDirectory());
      return (gitIgnored || extraExcluded) && !extraIncluded;
    }

    public void setIgnoreRules(String ignoreData) {
      ignoreRules.setRules(ignoreData);
    }

    @Override
    public String toString() {
      return name;
    }
  }

}

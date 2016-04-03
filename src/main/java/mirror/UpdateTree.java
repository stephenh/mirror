package mirror;

import static org.jooq.lambda.Seq.seq;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.lambda.Seq;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

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
  final PathRules explicitIncludes = new PathRules();

  public static UpdateTree newRoot() {
    return new UpdateTree();
  }

  private UpdateTree() {
    this.root = new Node(null, Update.newBuilder().setPath("").setDirectory(true).build());
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
    if (!update.getData().isEmpty()) {
      throw new IllegalArgumentException("UpdateTree should not have any data");
    }
    if (update.getPath().startsWith("/") || update.getPath().endsWith("/")) {
      throw new IllegalArgumentException("Update path should not start or end with slash: " + update.getPath());
    }
    List<Path> parts = Lists.newArrayList(Paths.get(update.getPath()));
    // find parent directory
    int i = 0;
    Node current = root;
    for (; i < parts.size() - 1; i++) {
      String name = parts.get(i).getFileName().toString();
      current = seq(current.children).findFirst(t -> t.name.equals(name)).orElseThrow(() -> {
        return new IllegalArgumentException("Directory " + name + " not found for update " + update.getPath());
      });
    }
    // now handle the last part which is the file name
    String name = parts.get(i).getFileName().toString();
    Optional<Node> existing = Seq.seq(current.children).findFirst(t -> t.name.equals(name));
    if (existing.isPresent()) {
      ensureStillAFileOrDirectory(existing.get(), update, name);
      existing.get().update = update;
      // TODO update if .gitignore
    } else {
      current.children.add(new Node(current, update));
      // kind of hacky to do it here
      if (name.equals(".gitignore")) {
        current.setIgnoreRules(update.getIgnoreString());
      }
    }
  }

  @VisibleForTesting
  List<Node> getChildren() {
    return root.children;
  }

  /** Either a directory or file within the tree. */
  public class Node {
    private final Node parent;
    private final String name;
    private final List<Node> children = new ArrayList<>();
    // should contain .gitignore + svn:ignore + custom excludes/includes
    private final PathRules ignoreRules = new PathRules();
    private Update update;
    // State == dirty, synced, partial-ignore/full-ignore?

    private Node(Node parent, Update update) {
      this.parent = parent;
      this.update = update;
      this.name = Paths.get(update.getPath()).getFileName().toString();
    }

    boolean isSameType(Node o) {
      return o != null && ((isFile() && o.isFile()) //
        || (isDirectory() && o.isDirectory())
        || (isSymlink() && o.isSymlink()));
    }

    boolean isNewer(Node o) {
      return o == null || getModTime() > o.getModTime();
    }

    @VisibleForTesting
    String getName() {
      return name;
    }

    @VisibleForTesting
    List<Node> getChildren() {
      return children;
    }

    long getModTime() {
      return update.getModTime();
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

    public Update getUpdate() {
      return update;
    }

    /** @param p should be a relative path, e.g. a/b/c.txt. */
    public boolean shouldIgnore() {
      boolean gitIgnored = Seq.iterate(parent, t -> t.parent).limitUntil(Objects::isNull).reverse().findFirst(n -> {
        // e.g. directory might be dir1/dir2, and p is dir1/dir2/foo.txt, we want
        // to call is match with just foo.txt, and not the dir1/dir2 prefix
        String relative = this.update.getPath().substring(n.update.getPath().length()).replaceAll("^/", "");
        return n.ignoreRules.hasMatchingRule(relative, this.isDirectory());
      }).isPresent();
      boolean customIncluded = explicitIncludes.hasMatchingRule(update.getPath(), isDirectory());
      return gitIgnored && !customIncluded;
    }

    public void setIgnoreRules(String ignoreData) {
      ignoreRules.setRules(ignoreData);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static void ensureStillAFileOrDirectory(Node e, Update update, String name) {
    if (!e.isDirectory() && update.getDirectory()) {
      throw new IllegalArgumentException("Adding directory " + name + " already exists as a file");
    } else if (e.isDirectory() && !update.getDirectory()) {
      throw new IllegalArgumentException("Adding file " + name + " already exists as a directory");
    }
  }

}

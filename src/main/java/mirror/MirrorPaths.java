package mirror;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import mirror.UpdateTree.Node;

/** A parameter object for the various path configs for a session. */
public class MirrorPaths {

  public final Path root;
  public final Path remoteRoot;
  private final PathRules includes;
  private final PathRules excludes;
  private final boolean debugAll;
  private final List<String> debugPrefixes;

  public static MirrorPaths forTesting(Path local) {
    return new MirrorPaths(local, null, new PathRules(), new PathRules(), false, new ArrayList<>());
  }

  public MirrorPaths(Path root, Path remoteRoot, PathRules includes, PathRules excludes, boolean debugAll, List<String> debugPrefixes) {
    this.root = root;
    this.remoteRoot = remoteRoot;
    this.includes = includes;
    this.excludes = excludes;
    this.debugAll = debugAll;
    this.debugPrefixes = debugPrefixes;
  }

  public void addParameters(InitialSyncRequest.Builder req) {
    req.addAllIncludes(includes.getLines());
    req.addAllExcludes(excludes.getLines());
    req.addAllDebugPrefixes(debugPrefixes);
  }

  public boolean isIncluded(String path, boolean directory) {
    return includes.matches(path, directory);
  }

  public boolean isExcluded(String path, boolean directory) {
    return excludes.matches(path, directory);
  }

  public boolean shouldDebug(Node node) {
    // avoid calcing the path if we have no prefixes anyway
    if (debugAll) {
      return true;
    }
    if (debugPrefixes.isEmpty()) {
      return false;
    }
    return shouldDebug(node.getPath());
  }

  public boolean shouldDebug(String path) {
    if (debugAll) {
      return true;
    }
    return debugPrefixes.stream().anyMatch(prefix -> path.startsWith(prefix));
  }

}

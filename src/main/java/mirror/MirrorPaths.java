package mirror;

import java.nio.file.Path;
import java.util.List;

/** A parameter object for the various path configs for a session. */
public class MirrorPaths {

  public final Path root;
  public final Path remoteRoot;
  public final PathRules includes;
  public final PathRules excludes;
  public final List<String> debugPrefixes;

  public MirrorPaths(Path root, Path remoteRoot, PathRules includes, PathRules excludes, List<String> debugPrefixes) {
    this.root = root;
    this.remoteRoot = remoteRoot;
    this.includes = includes;
    this.excludes = excludes;
    this.debugPrefixes = debugPrefixes;
  }

}

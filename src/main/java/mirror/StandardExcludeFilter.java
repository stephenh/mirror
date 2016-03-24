package mirror;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.jooq.lambda.Seq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filters out paths we don't want to sync.
 *
 * Currently a combination of .gitignore + hardcoded rules that work for me.
 */
class StandardExcludeFilter {

  private static final Logger log = LoggerFactory.getLogger(StandardExcludeFilter.class);
  private final Map<Path, List<FastIgnoreRule>> gitIgnores = new HashMap<>();

  public void addGitIgnore(Path relativeParent, Path p) throws IOException {
    List<FastIgnoreRule> rules = new ArrayList<>();
    for (String txt : FileUtils.readLines(p.toFile())) {
      if (txt.length() > 0 && !txt.startsWith("#") && !txt.equals("/")) { //$NON-NLS-1$ //$NON-NLS-2$
        FastIgnoreRule rule = new FastIgnoreRule(txt);
        if (!rule.isEmpty()) {
          rules.add(rule);
        }
      }
    }
    log.info("Storing .gitignore for {} = {}", relativeParent, rules);
    gitIgnores.put(relativeParent, rules);
  }

  /** @param p should be a relative path, e.g. a/b/c.txt. */
  public boolean test(Path p) {
    boolean gitIgnore = shouldGitIgnore(p);

    // my hacky includes for now
    boolean customExclude = anyPartMatches(p, s -> (s.startsWith(".") && !s.equals(".")) //
      || s.equals("tmp")
      || s.equals("temp")
      || s.equals("target")
      || s.endsWith(".class"));

    boolean include = anyPartMatches(
      p,
      s -> s.equals("src_managed") || s.endsWith("-SNAPSHOT.jar") || s.equals(".classpath") || s.equals(".project"));

    log.debug("{} exclude={}, include={}, gitIgnore={}", p, customExclude, include, gitIgnore);

    return (customExclude || gitIgnore) && !include;
  }

  private static boolean anyPartMatches(Path p, Predicate<String> f) {
    while (p != null && p.getFileName() != null) {
      if (f.test(p.getFileName().toString())) {
        return true;
      }
      p = p.getParent();
    }
    return false;
  }

  /** @param p should be a relative path, e.g. a/b/c.txt. */
  private boolean shouldGitIgnore(Path p) {
    return Seq
      .iterate(p.getParent(), c -> c.getParent())
      .limitUntil(Objects::isNull)
      .append(Paths.get("")) // need an extra check for the root path
      .reverse()
      .findFirst(c -> shouldIgnore(c, p))
      .isPresent();
  }

  private boolean shouldIgnore(Path directory, Path p) {
    // e.g. directory might be dir1/dir2, and p is dir1/dir2/foo.txt, we want
    // to call is match with just foo.txt, and not the dir1/dir2 prefix
    String relative = p.toString().substring(directory.toString().length()).replaceAll("^/", "");
    List<FastIgnoreRule> rules = gitIgnores.get(directory);
    if (rules != null) {
      for (FastIgnoreRule rule : rules) {
        if (rule.isMatch(relative, p.toFile().isDirectory()) && rule.getResult()) {
          return true;
        }
      }
    }
    return false;
  }
}

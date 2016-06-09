package mirror;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.ignore.FastIgnoreRule;

/**
 * Keeps a list of .gitignore-style rules, and handles eval paths against the rules.
 */
public class PathRules {

  private final List<FastIgnoreRule> rules = new ArrayList<>();

  public PathRules() {
  }

  public PathRules(String lines) {
    setRules(lines);
  }

  public PathRules(String... lines) {
    setRules(lines);
  }

  /** @param lines the new rules, new line delimited, e.g. from a .gitignore file. */
  public void setRules(String lines) {
    setRules(lines.split("\n"));
  }

  public void setRules(String... lines) {
    rules.clear();
    for (String line : lines) {
      if (line.length() > 0 && !line.startsWith("#") && !line.equals("/")) {
        FastIgnoreRule rule = new FastIgnoreRule(line);
        if (!rule.isEmpty()) {
          rules.add(rule);
        }
      }
    }
  }

  /** @return true if we should ignore {@code path} */
  public boolean shouldIgnore(String path, boolean isDirectory) {
    boolean result = false;
    for (FastIgnoreRule rule : rules) {
      if (rule.isMatch(path, isDirectory)) {
        result = rule.getResult();
        // don't break, keep going so we can look for a "!..." after this
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return rules.toString();
  }

}

package mirror;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.jooq.lambda.Seq;

/**
 * Keeps a list of .gitignore-style rules, and handles eval paths against the rules.
 */
public class PathRules {

  private final List<Pair<String, FastIgnoreRule>> rules = new ArrayList<>();

  public PathRules() {
  }

  public PathRules(String lines) {
    setRules(lines);
  }

  public PathRules(String... lines) {
    setRules(lines);
  }

  public PathRules(List<String> lines) {
    setRules(lines);
  }

  /** @param lines the new rules, new line delimited, e.g. from a .gitignore file. */
  public void setRules(String lines) {
    setRules(lines.split("\n"));
  }

  public void addRules(String... lines) {
    for (String line : lines) {
      addRule(line);
    }
  }

  public void setRules(String... lines) {
    setRules(Arrays.asList(lines));
  }

  public void setRules(List<String> lines) {
    rules.clear();
    for (String line : lines) {
      if (line.length() > 0 && !line.startsWith("#") && !line.equals("/")) {
        addRule(line);
      }
    }
  }

  public void addRule(String line) {
    FastIgnoreRule rule = new FastIgnoreRule(line);
    if (!rule.isEmpty()) {
      rules.add(Pair.of(line, rule));
    }
  }

  /** @return true if we should ignore {@code path} */
  public boolean matches(String path, boolean isDirectory) {
    boolean result = false;
    for (Pair<String, FastIgnoreRule> t : rules) {
      FastIgnoreRule rule = t.getRight();
      if (rule.isMatch(path, isDirectory)) {
        result = rule.getResult();
        // don't break, keep going so we can look for a "!..." after this
      }
    }
    return result;
  }

  public List<String> getLines() {
    return Seq.seq(rules).map(t -> t.getLeft()).toList();
  }

  public boolean hasAnyRules() {
    return !rules.isEmpty();
  }

  @Override
  public String toString() {
    return rules.toString();
  }

}

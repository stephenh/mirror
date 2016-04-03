package mirror;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.ignore.FastIgnoreRule;

public class PathRules {

  private final List<FastIgnoreRule> rules = new ArrayList<>();

  public void setRules(String lines) {
    rules.clear();
    for (String line : lines.split("\n")) {
      if (line.length() > 0 && !line.startsWith("#") && !line.equals("/")) {
        FastIgnoreRule rule = new FastIgnoreRule(line);
        if (!rule.isEmpty()) {
          rules.add(rule);
        }
      }
    }
  }

  public boolean hasMatchingRule(String path, boolean isDirectory) {
    for (FastIgnoreRule rule : rules) {
      if (rule.isMatch(path, isDirectory) && rule.getResult()) {
        // technically need to keep going to look for a "!...", e.g. add a test case for:
        //   $ cat .gitignore
        //   # exclude everything except directory foo/bar
        //   /*
        //   !/foo
        //   /foo/*
        //   !/foo/bar
        return true;
      }
    }
    return false;
  }

}

package mirror;

import java.nio.file.Path;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StandardExcludeFilter implements Predicate<Path> {

  private static final Logger log = LoggerFactory.getLogger(StandardExcludeFilter.class);

  @Override
  public boolean test(Path p) {
    boolean exclude = anyPartMatches(p, s -> (s.startsWith(".") && !s.equals(".")) //
      || s.equals("tmp")
      || s.equals("temp")
      || s.equals("target")
      || s.endsWith(".class"));
    boolean include = anyPartMatches(
      p,
      s -> s.equals("src_managed") || s.endsWith("-SNAPSHOT.jar") || s.equals(".classpath") || s.equals(".project"));
    log.debug("{} exclude={}, include={}", p, exclude, include);
    return exclude && !include;
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
}

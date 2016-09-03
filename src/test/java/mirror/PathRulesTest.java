package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;

import org.junit.Test;

public class PathRulesTest {

  @Test
  public void testIgnore() {
    PathRules r = new PathRules("*.txt");
    assertThat(r.matches("foo.txt", false), is(true));
  }

  @Test
  public void testUnignore() {
    PathRules r = new PathRules("*.txt", "!foo.txt");
    assertThat(r.matches("foo.txt", false), is(false));
  }

  @Test
  public void testUnignoreSubdirectory() {
    // example taken from git documentation
    PathRules r = new PathRules("/*", "!/foo", "/foo/*", "!/foo/bar");
    assertThat(r.matches("a", false), is(true));
    assertThat(r.matches("foo/a", false), is(true));
    assertThat(r.matches("foo/bar/a", false), is(false));
  }

  @Test
  public void testConfigDirectory() {
    PathRules includes = new PathRules();
    PathRules excludes = new PathRules();
    Mirror.setupIncludesAndExcludes(includes, excludes, new ArrayList<>(), new ArrayList<>(), true);
    // we'll sync the config directory even if it's gitignore'd
    assertThat(includes.matches("multi-product/config/fabric.src", false), is(true));
    // but we won't explicitly include it if it happens to be somewhere false
    assertThat(includes.matches("multi-product/src/config/fabric.src", false), is(false));
    // and we won't include svn directories inside of the config directory
    assertThat(includes.matches("multi-product/config/.svn/stuff", false), is(false));
  }

}

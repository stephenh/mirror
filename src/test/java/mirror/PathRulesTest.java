package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;

public class PathRulesTest {

  @Test
  public void testIgnore() {
    PathRules r = new PathRules("*.txt");
    assertThat(r.shouldIgnore("foo.txt", false), is(true));
  }

  @Test
  public void testUnignore() {
    PathRules r = new PathRules("*.txt", "!foo.txt");
    assertThat(r.shouldIgnore("foo.txt", false), is(false));
  }
  

  @Test
  public void testUnignoreSubdirectory() {
    // example taken from git documentation
    PathRules r = new PathRules("/*", "!/foo", "/foo/*", "!/foo/bar");
    assertThat(r.shouldIgnore("a", false), is(true));
    assertThat(r.shouldIgnore("foo/a", false), is(true));
    assertThat(r.shouldIgnore("foo/bar/a", false), is(false));
  }


}

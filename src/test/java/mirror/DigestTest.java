package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Paths;

import org.junit.Test;

public class DigestTest {

  @Test
  public void testFirstImplementation() throws Exception {
    assertThat(Digest.getHash(Paths.get("./src/test/resources/bar.txt")), is("c157a79031e1c4f85931829bc5fc552"));
  }

}

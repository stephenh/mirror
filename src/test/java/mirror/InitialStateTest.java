package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class InitialStateTest {

  private final static byte[] data = new byte[] { 1, 2, 3, 4 };
  private final Path root = Paths.get("./");
  private final StubFileAccess fileAccess = new StubFileAccess();
  private final InitialState state = new InitialState(root, fileAccess);

  @Test
  public void fillsInModTime() throws Exception {
    // given a local file
    fileAccess.write(root.resolve("./foo.txt"), ByteBuffer.wrap(data));
    List<Update> in = new ArrayList<>();
    in.add(Update.newBuilder().setPath("./foo.txt").build());
    List<Update> out = state.prepare(in);
    // then we fill in the file modification time
    assertThat(out.get(0).getModTime(), is(1L));
  }

}

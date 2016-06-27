package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class MirrorSessionTest {

  private final Path root = Paths.get(".");
  private final StubFileAccess fileAccess = new StubFileAccess();
  private final List<Update> fileUpdates = new ArrayList<>();
  private final FileWatcher fileWatcher = Mockito.mock(FileWatcher.class);
  private final MirrorSession session = new MirrorSession(root, fileAccess, fileWatcher);

  @Before
  public void before() throws Exception {
    Mockito.when(fileWatcher.performInitialScan(Mockito.any())).thenReturn(fileUpdates);
  }

  @Test
  public void shouldReturnPathsFromCalcInitialState() throws Exception {
    fileUpdates.add(Update.newBuilder().setPath("foo.txt").build());

    List<Update> updates = session.calcInitialState();
    assertThat(updates.size(), is(2));
    assertThat(updates.get(0).getPath(), is(""));
    assertThat(updates.get(1).getPath(), is("foo.txt"));
  }

  @Test
  public void shouldSkipIgnoredFilesFromCalcInitialState() throws Exception {
    fileUpdates.add(Update.newBuilder().setPath("foo.txt").build());
    fileUpdates.add(Update.newBuilder().setPath("foo.log").build());
    fileUpdates.add(Update.newBuilder().setPath(".gitignore").setIgnoreString("*.txt").build());

    List<Update> updates = session.calcInitialState();
    assertThat(updates.size(), is(3));
    assertThat(updates.get(0).getPath(), is(""));
    assertThat(updates.get(1).getPath(), is("foo.log"));
    assertThat(updates.get(2).getPath(), is(".gitignore"));
  }
}

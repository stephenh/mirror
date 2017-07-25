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

import io.grpc.stub.StreamObserver;
import mirror.tasks.StubTaskFactory;

public class MirrorSessionTest {

  private final Path root = Paths.get(".");
  private final StubFileAccess fileAccess = new StubFileAccess();
  private final List<Update> fileUpdates = new ArrayList<>();
  private final FileWatcherFactory fileWatcherFactory = Mockito.mock(FileWatcherFactory.class);
  private final FileWatcher fileWatcher = Mockito.mock(FileWatcher.class);
  private final StubTaskFactory taskFactory = new StubTaskFactory();
  private MirrorSession session;

  @Before
  public void before() throws Exception {
    Mockito.when(fileWatcherFactory.newWatcher(Mockito.any(), Mockito.any())).thenReturn(fileWatcher);
    Mockito.when(fileWatcher.performInitialScan()).thenReturn(fileUpdates);
    session = new MirrorSession(
      taskFactory,
      new MirrorPaths(root, null, new PathRules("*.jar"), new PathRules(), new ArrayList<>()),
      fileAccess,
      fileWatcherFactory);
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

  @Test
  public void shouldReturnExtraIncludedFilesFromCalcInitialState() throws Exception {
    fileUpdates.add(Update.newBuilder().setPath("foo.txt").build());
    fileUpdates.add(Update.newBuilder().setPath("build/foo.log").build());
    fileUpdates.add(Update.newBuilder().setPath("build/foo.jar").build());
    fileUpdates.add(Update.newBuilder().setPath(".gitignore").setIgnoreString("build/").build());

    List<Update> updates = session.calcInitialState();
    assertThat(updates.size(), is(4));
    assertThat(updates.get(0).getPath(), is(""));
    assertThat(updates.get(1).getPath(), is("foo.txt"));
    assertThat(updates.get(2).getPath(), is(".gitignore"));
    assertThat(updates.get(3).getPath(), is("build/foo.jar"));
  }

  @Test
  public void shouldTimeoutAfterTwoMinutes() throws Exception {
    // TODO
    session.calcInitialState();
    session.diffAndStartPolling(new OutgoingConnectionImpl(new StreamObserver<Update>() {
      @Override
      public void onNext(Update value) {
      }

      @Override
      public void onError(Throwable t) {
      }

      @Override
      public void onCompleted() {
      }
    }));
  }

}

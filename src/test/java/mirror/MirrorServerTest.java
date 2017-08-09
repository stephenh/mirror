package mirror;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import mirror.tasks.StubTaskFactory;

public class MirrorServerTest {

  private final List<Update> fileUpdates = new ArrayList<>();
  private final FileWatcherFactory fileWatcherFactory = Mockito.mock(FileWatcherFactory.class);
  private final FileWatcher fileWatcher = Mockito.mock(FileWatcher.class);
  private final StubFileAccess rootFileAccess = new StubFileAccess();
  private final StubFileAccess sessionFileAccess = new StubFileAccess();
  private final FileAccessFactory accessFactory = (path) -> sessionFileAccess;
  private final StubTaskFactory taskFactory = new StubTaskFactory();
  private MirrorServer server;

  @Before
  public void before() throws Exception {
    Mockito.when(fileWatcherFactory.newWatcher(Mockito.any(), Mockito.any())).thenReturn(fileWatcher);
    Mockito.when(fileWatcher.performInitialScan()).thenReturn(fileUpdates);
    server = new MirrorServer(taskFactory, accessFactory, fileWatcherFactory, rootFileAccess);
    rootFileAccess.mkdir(Paths.get("home"));
  }

  @Test
  public void shouldStartAValidRequest() {
    // Given a valid initial request
    InitialSyncRequest request = InitialSyncRequest.newBuilder().setRemotePath("home").build();
    // When the client attempts to connect
    StubObserver<InitialSyncResponse> response = new StubObserver<>();
    server.initialSync(request, response);
    // Then we give them an error message
    assertThat(server.numberOfSessions(), is(1));
  }

  @Test
  public void shouldWarnAnOlderClient() {
    // Given a valid request with an older client
    InitialSyncRequest request = InitialSyncRequest.newBuilder().setRemotePath("home").setVersion("older").build();
    // When the client attempts to connect
    StubObserver<InitialSyncResponse> response = new StubObserver<>();
    server.initialSync(request, response);
    // Then we give them an error message
    assertThat(response.values.get(0).getWarningMessagesList(), hasItems("Server version unspecified does not match client version older"));
  }

  @Test
  public void shouldRejectRequestsWithABadRootPath() {
    // Given a root path that is invalid
    InitialSyncRequest request = InitialSyncRequest.newBuilder().setRemotePath("badvalue").build();
    // When the client attempts to connect
    StubObserver<InitialSyncResponse> response = new StubObserver<>();
    server.initialSync(request, response);
    // Then we give them an error message
    assertThat(response.values.get(0).getErrorMessage(), is("Path badvalue does not exist on the server"));
    // And don't actually start a session
    assertThat(server.numberOfSessions(), is(0));
  }

}

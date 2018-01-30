package mirror.watchman;

import static com.google.common.collect.Lists.newArrayList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import mirror.Update;

/**
 * Tests {@link WatchmanFileWatcher}.
 */
public class WatchmanFileWatcherTest {

  private final Path root = Paths.get("/");
  private Watchman wm = null;
  private WatchmanFactory factory;
  private BlockingQueue<Update> queue = new ArrayBlockingQueue<Update>(100);
  private Map<String, Object> queryParams = new HashMap<>();

  @Before
  public void setupMocks() {
    queryParams.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
    factory = () -> {
      wm = mock(Watchman.class);
      when(wm.query("query", "/", queryParams)).thenReturn(ImmutableMap.of("clock", "foo", "files", new ArrayList<>()));
      return wm;
    };
  }

  @Test
  public void shouldRestartWatchmanWhenOverflowHappens() throws Exception {
    // given a file watcher that has been initialized
    WatchmanFileWatcher fw = new WatchmanFileWatcher(factory, root, queue);
    fw.performInitialScan();
    fw.onStart();
    // when a read gets an overflow
    Watchman originalWm = wm;
    when(wm.read()).thenThrow(new WatchmanOverflowException());
    fw.runOneLoop();
    // then we've created a new wm connection
    assertThat(wm, is(not(sameInstance(originalWm))));
    // and we closed the previous wm connection
    verify(originalWm).close();
    // and deleted the old watch
    verify(wm).query("watch-del", "/");
    // and re-scanned the new wm connection
    verify(wm).query("watch", "/");
    verify(wm).query("query", "/", queryParams);
    verify(wm).query(eq("subscribe"), eq("/"), eq("mirror"), anyMap());
    verifyNoMoreInteractions(wm);
  }

}

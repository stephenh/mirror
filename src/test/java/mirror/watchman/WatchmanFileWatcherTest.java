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

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import mirror.MirrorPaths;
import mirror.Update;

/**
 * Tests {@link WatchmanFileWatcher}.
 */
public class WatchmanFileWatcherTest {

  private static final String absRoot = "/home/someuser/someroot";
  private static final Path root = Paths.get(absRoot);
  private Watchman wm = null;
  private WatchmanFactory factory;
  private BlockingQueue<Update> queue = new ArrayBlockingQueue<Update>(100);
  private Map<String, Object> queryParams = new HashMap<>();

  @Test
  public void shouldHandleWatchProjectReturningAPrefix() throws Exception {
    // given a file watcher that has been initialized
    setupWatchmanWithRelativePath("/home");
    WatchmanFileWatcher fw = new WatchmanFileWatcher(factory, MirrorPaths.forTesting(root), queue);
    fw.performInitialScan();
    fw.onStart();
    verify(wm).query("watch-project", absRoot);
    verify(wm).query("query", "/home", queryParams);
    verify(wm).query(
      "subscribe",
      "/home",
      "mirror",
      ImmutableMap.of(
        "expression",
        newArrayList("dirname", "someuser/someroot"),
        "fields",
        newArrayList("name", "exists", "mode", "mtime_ms"),
        "since",
        "foo"));
    verifyNoMoreInteractions(wm);
  }

  @Test
  public void shouldRestartWatchmanWhenOverflowHappens() throws Exception {
    // given a file watcher that has been initialized
    setupWatchmanForNoRelativePath();
    WatchmanFileWatcher fw = new WatchmanFileWatcher(factory, MirrorPaths.forTesting(root), queue);
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
    verify(wm).query("watch-del", absRoot);
    // and re-scanned the new wm connection
    verify(wm).query("watch-project", absRoot);
    verify(wm).query("query", absRoot, queryParams);
    verify(wm).query(eq("subscribe"), eq(absRoot), eq("mirror"), anyMap());
    verifyNoMoreInteractions(wm);
  }

  private void setupWatchmanForNoRelativePath() {
    factory = () -> {
      wm = mock(Watchman.class);
      when(wm.query("watch-project", absRoot)).thenReturn(ImmutableMap.of("watch", absRoot));
      queryParams.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
      when(wm.query("query", absRoot, queryParams)).thenReturn(ImmutableMap.of("clock", "foo", "files", new ArrayList<>()));
      return wm;
    };
  }

  private void setupWatchmanWithRelativePath(String watchRoot) {
    factory = () -> {
      wm = mock(Watchman.class);
      when(wm.query("watch-project", absRoot)).thenReturn(ImmutableMap.of("watch", watchRoot, "relative_path", "someuser/someroot"));
      queryParams.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
      queryParams.put("path", newArrayList("someuser/someroot"));
      when(wm.query("query", watchRoot, queryParams)).thenReturn(ImmutableMap.of("clock", "foo", "files", new ArrayList<>()));
      return wm;
    };
  }

}

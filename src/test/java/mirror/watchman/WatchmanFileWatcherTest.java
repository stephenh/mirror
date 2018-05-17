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
import java.util.Optional;
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

  private static final String absRoot = "/foo/bar/zaz";
  private static final Path root = Paths.get(absRoot);
  private Watchman wm = null;
  private WatchmanFactory factory;
  private BlockingQueue<Update> queue = new ArrayBlockingQueue<Update>(100);
  private Map<String, Object> queryParams = new HashMap<>();

  @Test
  public void shouldHandleWatchProjectReturningAPrefix() throws Exception {
    // given we watch /foo/bar/zaz and /foo is already the watch root
    setupWatchman("/foo", Optional.of("bar/zaz"));
    WatchmanFileWatcher fw = new WatchmanFileWatcher(factory, MirrorPaths.forTesting(root), queue);
    fw.performInitialScan();
    fw.onStart();
    verify(wm).query("watch-project", absRoot);
    verify(wm).query("query", "/foo", queryParams);
    verify(wm).query(
      "subscribe",
      "/foo",
      "mirror",
      ImmutableMap.of("relative_root", "bar/zaz", "fields", newArrayList("name", "exists", "mode", "mtime_ms"), "since", "foo"));
    verifyNoMoreInteractions(wm);
  }

  @Test
  public void shouldRestartWatchmanWhenOverflowHappens() throws Exception {
    // given we watch /foo/bar/zaz and that is our watch root
    setupWatchman("/foo/bar/zaz", Optional.empty());
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

  private void setupWatchman(String watchRoot, Optional<String> relativePath) {
    factory = () -> {
      // I hate mocks
      wm = mock(Watchman.class);
      // mock the watch-project response
      Map<String, Object> watchResponse = relativePath.isPresent() // 
        ? ImmutableMap.of("watch", watchRoot, "relative_path", relativePath.get()) //
        : ImmutableMap.of("watch", watchRoot);
      when(wm.query("watch-project", absRoot)).thenReturn(watchResponse);
      // mock the query response
      queryParams.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
      if (relativePath.isPresent()) {
        queryParams.put("relative_root", relativePath.get());
      }
      when(wm.query("query", watchRoot, queryParams)).thenReturn(ImmutableMap.of("clock", "foo", "files", new ArrayList<>()));
      return wm;
    };
  }

}

package mirror.watchman;

import static com.google.common.collect.Lists.newArrayList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.facebook.watchman.Callback;
import com.facebook.watchman.WatchmanClient.SubscriptionDescriptor;
import com.google.common.collect.ImmutableMap;

import mirror.MirrorPaths;
import mirror.Update;

/**
 * Tests {@link WatchmanFileWatcher}.
 */
public class WatchmanFileWatcherTest {

  private final class StubDescriptor extends SubscriptionDescriptor {
    @Override
    public String root() {
      return null;
    }

    @Override
    public String name() {
      return null;
    }
  }

  private static final String absRoot = "/foo/bar/zaz";
  private static final Path root = Paths.get(absRoot);
  private Watchman wm = null;
  private BlockingQueue<Update> queue = new ArrayBlockingQueue<Update>(100);
  private Map<String, Object> queryParams = new HashMap<>();

  @Test
  public void shouldHandleWatchProjectReturningAPrefix() throws Exception {
    // given we watch /foo/bar/zaz and /foo is already the watch root
    setupWatchman("/foo", Optional.of("bar/zaz"));
    WatchmanFileWatcher fw = new WatchmanFileWatcher(wm, MirrorPaths.forTesting(root), queue);
    fw.performInitialScan();
    fw.onStart();
    verify(wm).run("watch-project", absRoot);
    verify(wm).run("query", "/foo", queryParams);
    verify(wm)
      .subscribe(
        eq(Paths.get("/foo")),
        eq(ImmutableMap.of("relative_root", "bar/zaz", "fields", newArrayList("name", "exists", "mode", "mtime_ms"), "since", "foo")),
        Mockito.any());
    verifyNoMoreInteractions(wm);
  }

  @Test
  public void shouldRestartWatchmanWhenOverflowHappens() throws Exception {
    // given we watch /foo/bar/zaz and that is our watch root
    setupWatchman("/foo/bar/zaz", Optional.empty());
    WatchmanFileWatcher fw = new WatchmanFileWatcher(wm, MirrorPaths.forTesting(root), queue);
    fw.performInitialScan();
    // we'll start the first description
    ArgumentCaptor<Callback> callback = ArgumentCaptor.forClass(Callback.class);
    SubscriptionDescriptor sub = new StubDescriptor();
    when(wm.subscribe(Mockito.any(), Mockito.any(), callback.capture())).thenReturn(sub);
    fw.onStart();
    // when a read gets an overflow
    callback.getValue().call(ImmutableMap.of("error", "IN_Q_OVERFLOW"));
    fw.runOneLoop();
    // then we've un-subscribed
    verify(wm).unsubscribe(sub);
    // and deleted the old watch
    verify(wm).run("watch-del", absRoot);
    // and re-scanned the new wm connection
    verify(wm, times(2)).run("watch-project", absRoot);
    verify(wm, times(2)).run("query", absRoot, queryParams);
    verify(wm, times(2)).subscribe(eq(Paths.get(absRoot)), anyMap(), Mockito.any(Callback.class));
    verifyNoMoreInteractions(wm);
  }

  private void setupWatchman(String watchRoot, Optional<String> relativePath) {
    // I hate mocks
    wm = mock(Watchman.class);
    // mock the watch-project response
    Map<String, Object> watchResponse = relativePath.isPresent() // 
      ? ImmutableMap.of("watch", watchRoot, "relative_path", relativePath.get()) //
      : ImmutableMap.of("watch", watchRoot);
    when(wm.run("watch-project", absRoot)).thenReturn(watchResponse);
    // mock the query response
    queryParams.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
    if (relativePath.isPresent()) {
      queryParams.put("relative_root", relativePath.get());
    }
    when(wm.run("query", watchRoot, queryParams)).thenReturn(ImmutableMap.of("clock", "foo", "files", new ArrayList<>()));
  }

}

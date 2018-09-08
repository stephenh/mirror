package mirror.watchman;

import static com.google.common.collect.Lists.newArrayList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static mirror.Utils.resetIfInterrupted;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.watchman.Callback;
import com.facebook.watchman.WatchmanClient.SubscriptionDescriptor;
import com.google.protobuf.TextFormat;

import jnr.posix.FileStat;
import mirror.FileWatcher;
import mirror.LoggingConfig;
import mirror.MirrorPaths;
import mirror.Update;
import mirror.UpdateTree;
import mirror.tasks.TaskFactory;
import mirror.tasks.ThreadBasedTaskFactory;

/**
 * A {@link FileWatcher} that uses <a href="https://facebook.github.io/watchman">watchman</a>.
 */
public class WatchmanFileWatcher implements FileWatcher {

  private static final Logger log = LoggerFactory.getLogger(WatchmanFileWatcher.class);
  private final MirrorPaths config;
  private final Watchman wm;
  private final Path ourRoot;
  private final BlockingQueue<Update> queue;
  private final BlockingQueue<Exception> exceptions = new LinkedBlockingQueue<>();
  // we may ask to watch /home/foo/bar, but watchman decides to watch /home/foo
  private volatile String watchmanRoot;
  private volatile Optional<String> watchmanPrefix;
  private volatile String initialScanClock;
  private volatile SubscriptionDescriptor subscription;

  /** Main method for doing manual debugging/observation of behavior. */
  public static void main(String[] args) throws Exception {
    LoggingConfig.initWithTracing();
    TaskFactory f = new ThreadBasedTaskFactory();
    Path testDirectory = Paths.get("/home/stephen/dir1");
    BlockingQueue<Update> queue = new LinkedBlockingQueue<>();
    MirrorPaths config = MirrorPaths.forTesting(testDirectory);
    WatchmanFileWatcher w = new WatchmanFileWatcher(WatchmanImpl.createIfAvailable().get(), config, queue);
    log.info("Starting performInitialScan");
    List<Update> initialScan = w.performInitialScan();
    initialScan.forEach(node -> {
      log.info("Initial: " + UpdateTree.toDebugString(node));
    });
    f.runTask(w);
    while (true) {
      log.info("Update: " + UpdateTree.toDebugString(queue.take()));
    }
  }

  public WatchmanFileWatcher(Watchman wm, MirrorPaths config, BlockingQueue<Update> queue) {
    this.config = config;
    this.wm = wm;
    try {
      // If we get passed /home/foo/./path watchman's path sensitiveness check complains,
      // so turn it into /home/foo/path.
      this.ourRoot = config.root.toFile().getCanonicalFile().toPath();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    this.queue = queue;
  }

  @Override
  public void onStart() {
    try {
      startSubscription();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void onInterrupt() {
    try {
      log.debug("Stopping watchman");
      wm.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Duration runOneLoop() throws InterruptedException {
    try {
      // Keep blocking until we get more push notifications
      throw exceptions.take();
    } catch (WatchmanOverflowException e) {
      try {
        // try resetting the overflow
        wm.unsubscribe(subscription);
        wm.run("watch-del", watchmanRoot);
        startWatchAndInitialFind();
        startSubscription();
      } catch (Exception e2) {
        log.error("Could not reset watchman", e2);
      }
    } catch (InterruptedException e) {
      // BserEofException
      // shutting down
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  @Override
  public List<Update> performInitialScan() throws Exception {
    startWatchAndInitialFind();
    List<Update> updates = new ArrayList<>(queue.size());
    queue.drainTo(updates);
    return updates;
  }

  @SuppressWarnings("unchecked")
  private void putFiles(Map<String, Object> response) {
    List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");
    if (files == null) {
      throw new RuntimeException("Invalid response " + response);
    }
    files.forEach(this::putFile);
  }

  private void putFile(Map<String, Object> file) {
    int mode = ((Number) file.get("mode")).intValue();
    long mtime = ((Number) file.get("mtime_ms")).longValue();
    Object name = file.get("name");
    if (!(name instanceof String)) {
      return; // ignore non-utf8 file names as they are likely corrupted
    }
    resetIfInterrupted(() -> {
      Update.Builder ub = Update
        .newBuilder()
        .setPath((String) name)
        .setDelete(!(boolean) file.get("exists"))
        .setModTime(mtime)
        .setDirectory(isFileStatType(mode, FileStat.S_IFDIR))
        .setExecutable(isExecutable(mode))
        .setLocal(true);
      readSymlinkTargetIfNeeded(ub, mode);
      setIgnoreStringIfNeeded(ub);
      clearModTimeIfADelete(ub);
      Update u = ub.build();
      if (log.isTraceEnabled()) {
        log.trace("Queueing: " + TextFormat.shortDebugString(u));
      }
      if (config != null && config.shouldDebug(ub.getPath())) {
        log.info("Queueing: " + TextFormat.shortDebugString(u));
      }
      queue.put(u);
    });
  }

  private void startWatchAndInitialFind() throws Exception {
    // This will be a no-op after the first execution, as we don't currently clean up on our watches.
    Map<String, Object> result = wm.run("watch-project", ourRoot.toString());
    watchmanRoot = (String) result.get("watch");
    watchmanPrefix = Optional.ofNullable((String) result.get("relative_path"));
    log.info("Watchman root is {}", watchmanRoot);

    Map<String, Object> params = new HashMap<>();
    params.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
    watchmanPrefix.ifPresent(prefix -> {
      params.put("relative_root", prefix);
    });
    Map<String, Object> r = wm.run("query", watchmanRoot, params);
    initialScanClock = (String) r.get("clock");
    putFiles(r);
  }

  private void startSubscription() throws Exception {
    Map<String, Object> params = new HashMap<>();
    // Pass since b/c we don't need to be re-sent everything that we already saw in performInitialScan.
    params.put("since", initialScanClock);
    params.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
    watchmanPrefix.ifPresent(prefix -> {
      params.put("relative_root", prefix);
    });
    subscription = wm.subscribe(Paths.get(watchmanRoot), params, new Callback() {
      @Override
      public void call(Map<String, Object> message) {
        // this callback happens on a WatchmanClient thread, so any exceptions we want
        // to capture and put into the exceptions queue so our pumping thread can see it
        try {
          if (message.containsKey("error")) {
            if (((String) message.get("error")).contains("IN_Q_OVERFLOW")) {
              throw new WatchmanOverflowException();
            }
            throw new RuntimeException("Watchman error: " + message.get("error"));
          }
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> files = (List<Map<String, Object>>) message.get("files");
          files.forEach(WatchmanFileWatcher.this::putFile);
        } catch (Exception e) {
          exceptions.add(e);
        }
      }
    });
  }

  // The modtime from watchman is the pre-deletion modtime; to be
  // considered newer, we increment the pre-deletiong modtime by
  // one. Currently that logic is in UpdateTree, so we just clear
  // out mod time here.
  private void clearModTimeIfADelete(Update.Builder ub) {
    if (ub.getDelete()) {
      ub.clearModTime();
    }
  }

  private void setIgnoreStringIfNeeded(Update.Builder ub) {
    if (ub.getPath().endsWith(".gitignore")) {
      try {
        Path path = ourRoot.resolve(ub.getPath());
        ub.setIgnoreString(FileUtils.readFileToString(path.toFile(), UTF_8));
      } catch (IOException e) {
        // ignore as the file probably disappeared
        log.debug("Exception reading .gitignore, assumed stale", e);
      }
    }
  }

  private void readSymlinkTargetIfNeeded(Update.Builder ub, int mode) {
    if (isFileStatType(mode, FileStat.S_IFLNK)) {
      readSymlinkTarget(ub);
    }
  }

  private void readSymlinkTarget(Update.Builder ub) {
    try {
      Path path = ourRoot.resolve(ub.getPath());
      Path symlink = Files.readSymbolicLink(path);
      String targetPath;
      if (symlink.isAbsolute()) {
        targetPath = path.getParent().normalize().relativize(symlink.normalize()).toString();
      } else {
        // the symlink is already relative, so we can leave it alone, e.g. foo.txt
        targetPath = symlink.toString();
      }
      ub.setSymlink(targetPath);
      // Ensure our modtime is of the symlink itself (I'm not actually
      // sure which modtime watchman sends back, but this is safest).
      ub.setModTime(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
    } catch (IOException e) {
      // ignore as the file probably disappeared
      log.debug("Exception reading symlink, assumed stale", e);
    }
  }

  private static boolean isFileStatType(int mode, int mask) {
    return (mode & FileStat.S_IFMT) == mask;
  }

  private static boolean isExecutable(int mode) {
    return (mode & FileStat.S_IXUGO) != 0;
  }
}

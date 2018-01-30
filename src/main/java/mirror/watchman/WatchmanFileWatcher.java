package mirror.watchman;

import static com.google.common.collect.Lists.newArrayList;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.buck.bser.BserDeserializer.BserEofException;
import com.google.protobuf.TextFormat;

import jnr.posix.FileStat;
import mirror.FileWatcher;
import mirror.LoggingConfig;
import mirror.Update;
import mirror.UpdateTree;
import mirror.tasks.TaskFactory;
import mirror.tasks.ThreadBasedTaskFactory;

/**
 * A {@link FileWatcher} that uses <a href="https://facebook.github.io/watchman">watchman</a>.
 */
public class WatchmanFileWatcher implements FileWatcher {

  private static final Logger log = LoggerFactory.getLogger(WatchmanFileWatcher.class);
  private final WatchmanFactory factory;
  private final Path root;
  private volatile Watchman wm;
  private volatile BlockingQueue<Update> queue;
  private volatile String initialScanClock;

  /** Main method for doing manual debugging/observation of behavior. */
  public static void main(String[] args) throws Exception {
    LoggingConfig.init();
    TaskFactory f = new ThreadBasedTaskFactory();
    Path testDirectory = Paths.get("/home/stephen/dir1");
    BlockingQueue<Update> queue = new LinkedBlockingQueue<>();
    WatchmanFileWatcher w = new WatchmanFileWatcher(WatchmanChannelImpl.createIfAvailable().get(), testDirectory, queue);
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

  public WatchmanFileWatcher(WatchmanFactory factory, Path root, BlockingQueue<Update> queue) {
    this.factory = factory;
    try {
      // If we get passed /home/foo/./path watchman's path sensitiveness check complains,
      // so turn it into /home/foo/path.
      this.root = root.toFile().getCanonicalFile().toPath();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    this.queue = queue;
  }

  @Override
  public void onStart() {
    try {
      // Start the watchman subscription, and pass "since" because we don't
      // need to be re-sent everything that we already saw in performInitialScan.
      //
      // Note that once we do this, our wm instance is basically dedicated
      // to this subscription because runOneLoop will make continual blocking
      // calls on Watchman.read to keep getting the latest updates, which
      // means we can't really send any other commands without having the
      // response of our new command and subscription responses mixed up.
      startSubscription();
    } catch (IOException e) {
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
      putFiles(wm.read());
    } catch (WatchmanOverflowException e) {
      try {
        wm.close();
        // try resetting the overflow
        wm = factory.newWatchman();
        wm.query("watch-del", root.toString());
        startWatchAndInitialFind();
        startSubscription();
      } catch (Exception e2) {
        log.error("Could not reset watchman", e2);
      }
    } catch (BserEofException e) {
      // shutting down
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  @Override
  public List<Update> performInitialScan() throws IOException, InterruptedException {
    wm = factory.newWatchman();
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
    resetIfInterrupted(() -> {
      Update.Builder ub = Update
        .newBuilder()
        .setPath((String) file.get("name"))
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
        log.trace("Putting: " + TextFormat.shortDebugString(u));
      }
      queue.put(u);
    });
  }

  private void startWatchAndInitialFind() throws IOException {
    // This will be a no-op after the first execution, as we don't currently clean up on our watches.
    wm.query("watch", root.toString());
    Map<String, Object> params = new HashMap<>();
    params.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
    Map<String, Object> r = wm.query("query", root.toString(), params);
    initialScanClock = (String) r.get("clock");
    putFiles(r);
  }

  private void startSubscription() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("since", initialScanClock);
    params.put("fields", newArrayList("name", "exists", "mode", "mtime_ms"));
    wm.query("subscribe", root.toString(), "mirror", params);
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
        Path path = root.resolve(ub.getPath());
        ub.setIgnoreString(FileUtils.readFileToString(path.toFile()));
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
      Path path = root.resolve(ub.getPath());
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

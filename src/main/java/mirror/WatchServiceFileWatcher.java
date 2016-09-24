package mirror;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.TextFormat;

import mirror.tasks.TaskFactory;
import mirror.tasks.TaskLogic;
import mirror.tasks.ThreadBasedTaskFactory;

/**
 * Recursively watches a directory for changes and sends them to a BlockingQueue for processing.
 *
 * All of the events that we fire should use paths relative to {@code rootDirectory},
 * e.g. if we're watching {@code /home/user/code/}, and {@code project-a/foo.txt changes},
 * the path of the event should be {@code project-a/foo.txt}.
 *
 * The observed behavior of renames is that if you have:
 * 
 * - mkdir dir1
 * - mkdir dir1/dir2
 * - touch dir1/dir2/foo.txt
 * - mv dir1 dirA
 * - touch dirA/dir2/foo.txt
 *
 * And rename dir1 to dirA, a WatchEvent is fired with DELETE dir1, CREATE
 * dirA, and nothing about dir2 or foo.txt (they are silently moved).
 *
 * This means our UpdateTree should treat dir1 being deleted as all of
 * it's children being deleted.
 *
 * (As an implementation detail, the same WatchKey instance will be used
 * for both dir1 and dirA.)
 *
 * This observed behavior of deletes if that if you have:
 * 
 * - dir1/dir2/foo.txt
 *
 * And delete dir1, then we'll get DELETE events for foo.txt, dir2, and
 * then dir1.
 *
 * Update Sept 2016: I'm basically abandoning the Java Watch Service due
 * to buggy behavior on Linux and only polling support on Mac OSX, e.g.
 * see:
 * 
 * https://www.reddit.com/r/java/comments/3vtv8i/beware_javaniofilewatchservice_is_subtly_broken/
 *
 * In theory the buggy behavior on Linux can be solved using maps to
 * track key -> path, but I've observed behavior (just by running
 * this class's test) where:
 *
 * - dir1 CREATE fired, put in map
 * - Rename dir1 to dir2
 * - dir1 DELETE fired, remove key from map
 * - dir2 CREATE fired, put new key in map
 * - dir1/foo.txt CREATE fired with the old key
 *
 * And even if we did keep the old key, it would point to the prior
 * path.
 *
 * For these reasons, I'm basically calling this FileWatcher impl
 * end-of-lifed and will work on a watchman-based version going
 * forward.
 */
public class WatchServiceFileWatcher implements TaskLogic, FileWatcher {

  private static final Logger log = LoggerFactory.getLogger(WatchServiceFileWatcher.class);
  // I originally used a guava BiMap, but was seeing inconsistencies where writes were not seen,
  // so instead we just maintain two separate ConcurrentHashMaps by hand
  private final Map<WatchKey, Path> keyToPath = new ConcurrentHashMap<>();
  private final Map<Path, WatchKey> pathToKey = new ConcurrentHashMap<>();
  private final TaskFactory taskFactory;
  private final Path rootDirectory;
  private final FileAccess fileAccess;
  private final BlockingQueue<Update> rawUpdates = new ArrayBlockingQueue<>(1_000_000);
  private final WatchService watchService;
  private final Debouncer debouncer;
  private volatile BlockingQueue<Update> queue;

  /** Main method for doing manual debugging/observation of behavior. */
  public static void main(String[] args) throws Exception {
    LoggingConfig.initWithTracing();
    TaskFactory f = new ThreadBasedTaskFactory();
    Path testDirectory = Paths.get("/home/stephen/foo");
    WatchServiceFileWatcher w = new WatchServiceFileWatcher(f, FileSystems.getDefault().newWatchService(), testDirectory);
    BlockingQueue<Update> queue = new LinkedBlockingQueue<>();
    w.performInitialScan(queue);
    f.runTask(w);
    while (true) {
      queue.take();
    }
  }

  public WatchServiceFileWatcher(TaskFactory taskFactory, WatchService watchService, Path rootDirectory) {
    this.taskFactory = taskFactory;
    this.watchService = watchService;
    this.rootDirectory = rootDirectory;
    fileAccess = new NativeFileAccess(rootDirectory);
    debouncer = new Debouncer();
  }

  /**
   * Initializes watches on the rootDirectory, and returns a list of all of
   * the file paths found while setting up listening hooks.
   *
   * This scan is performed on-thread and so this method blocks until complete.
   */
  public List<Update> performInitialScan(BlockingQueue<Update> queue) throws IOException, InterruptedException {
    this.queue = queue;
    // use onChangedPath because it has some try/catch logic
    onChangedPath(queue, rootDirectory);
    List<Update> updates = new ArrayList<>(queue.size());
    queue.drainTo(updates);
    return updates;
  }

  @Override
  public void onStart() {
    taskFactory.runTask(debouncer);
  }

  @Override
  public void onStop() {
    taskFactory.stopTask(debouncer);
    try {
      watchService.close();
    } catch (IOException e) {
      log.warn("Exception when shutting down the watch service", e);
    }
  }

  @Override
  public Duration runOneLoop() throws InterruptedException {
    try {
      WatchKey watchKey = watchService.take();
      // We can't use this:
      // Path parentDir = (Path) watchKey.watchable();
      // Because it might be stale when the directory renames, see:
      // https://bugs.openjdk.java.net/browse/JDK-7057783
      Path parentDir = keyToPath.get(watchKey);
      for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
        WatchEvent.Kind<?> eventKind = watchEvent.kind();
        if (log.isTraceEnabled()) {
          log.trace("WatchEvent {} {}", eventKind, watchEvent.context());
        }
        if (eventKind == OVERFLOW) {
          throw new RuntimeException("Watcher overflow");
        }
        if (parentDir == null) {
          log.error("Missing parentDir for " + watchKey + " " + watchKey.watchable() + ": " + watchEvent.context());
          if (log.isTraceEnabled()) {
            log.trace("pathToKey");
            for (Map.Entry<Path, WatchKey> e : pathToKey.entrySet()) {
              log.trace("   {} -> {}", e.getKey(), e.getValue());
            }
            log.trace("keyToPath");
            for (Map.Entry<WatchKey, Path> e : keyToPath.entrySet()) {
              log.trace("   {} -> {}", e.getKey(), e.getValue());
            }
          }
          continue;
        }
        Path child = parentDir.resolve((Path) watchEvent.context());
        if (eventKind == ENTRY_CREATE || eventKind == ENTRY_MODIFY) {
          onChangedPath(rawUpdates, child);
        } else if (eventKind == ENTRY_DELETE) {
          onRemovedPath(rawUpdates, child);
        }
      }
      // This returns a "stillValid" boolean, but observed behavior is that
      // so far stillValid=false only after we've deleted a directory, and
      // we already cancel/clear our watches for that in onRemovedPath
      watchKey.reset();
    } catch (IOException io) {
      throw new RuntimeException(io);
    } catch (ClosedWatchServiceException e) {
      // shutting down
    }
    return null;
  }

  private void onChangedPath(BlockingQueue<Update> queue, Path path) throws IOException, InterruptedException {
    // always recurse into directories so that even if we're excluding target/*,
    // if we are including target/scala-2.10/src_managed, then we can match those
    // paths even though we're ignoring some of the cruft around it
    try {
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        onChangedDirectory(queue, path);
      } else if (Files.isSymbolicLink(path)) {
        onChangedSymbolicLink(queue, path);
      } else {
        onChangedFile(queue, path);
      }
    } catch (NoSuchFileException | FileNotFoundException e) {
      // if a file gets deleted while getting the mod time/etc., just ignore it
    }
  }

  private void onRemovedPath(BlockingQueue<Update> queue, Path path) throws InterruptedException {
    // Note that we can't try and guess at a mod time, because System.currentTimeMillis might
    // actually already be stale, if a file was quickly deleted then recreated, and both events
    // are in our queue. (E.g. the new file's mod time could be after our guess when we see the delete
    // event.)
    put(queue, Update.newBuilder().setPath(toRelativePath(path)).setDelete(true).setLocal(true).build());
    // in case this was a deleted directory, we'll want to start watching it again if it's re-created
    WatchKey key = pathToKey.get(path);
    if (key != null) {
      unwatchDirectory(key, path);
    }
  }

  private void onChangedDirectory(BlockingQueue<Update> queue, Path directory) throws IOException, InterruptedException {
    if (pathToKey.containsKey(directory)) {
      // for existing directories, just emit an Update event
      putDir(queue, directory, lastModified(directory));
    } else {
      // Otherwise setup watchers on the whole tree.
      // Use walkFileTree because it in theory could minimize system calls, e.g.
      // like http://benhoyt.com/writings/scandir/. FWIW I don't actually know if
      // walkFileTree behaves this way, but it's cute.
      Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          putDir(queue, dir, attrs.lastModifiedTime().toMillis());
          watchDirectory(dir);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (attrs.isSymbolicLink()) {
            // reuse onChangedSymbolicLink even though it will do a file access for the
            // mod time, which we already have available in the attrs object
            onChangedSymbolicLink(queue, file);
          } else {
            putFile(queue, file, attrs.lastModifiedTime().toMillis());
          }
          return FileVisitResult.CONTINUE;
        }
      });
    }
  }

  private void watchDirectory(Path directory) throws IOException {
    WatchKey key = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    if (log.isTraceEnabled()) {
      log.trace("Putting " + key + " = " + directory);
    }
    keyToPath.put(key, directory);
    pathToKey.put(directory, key);
  }

  private void unwatchDirectory(WatchKey key, Path directory) {
    if (log.isTraceEnabled()) {
      log.trace("Removing " + key + " = " + directory);
    }
    pathToKey.remove(directory);
    keyToPath.remove(key);
    key.cancel();
  }

  private void onChangedFile(BlockingQueue<Update> queue, Path file) throws IOException {
    putFile(queue, file, lastModified(file));
  }

  private void onChangedSymbolicLink(BlockingQueue<Update> queue, Path path) throws IOException {
    Path symlink = Files.readSymbolicLink(path);
    String targetPath;
    if (symlink.isAbsolute()) {
      targetPath = path.getParent().toAbsolutePath().relativize(symlink).toString();
    } else {
      // the symlink is already relative, so we can leave it alone, e.g. foo.txt
      targetPath = symlink.toString();
    }
    String relativePath = toRelativePath(path);
    log.trace("Symlink {}, relative={}, target={}", path, relativePath, targetPath);
    put(queue, Update.newBuilder().setPath(relativePath).setSymlink(targetPath).setModTime(lastModified(path)).setLocal(true).build());
  }

  private void putDir(BlockingQueue<Update> queue, Path dir, long modTime) {
    put(queue, Update.newBuilder().setPath(toRelativePath(dir)).setDirectory(true).setLocal(true).setModTime(modTime).build());
  }

  private void putFile(BlockingQueue<Update> queue, Path file, long modTime) throws IOException {
    Update.Builder b = Update.newBuilder().setPath(toRelativePath(file)).setDirectory(false).setLocal(true).setModTime(modTime);
    // In theory we should read this in the debouncer, but performInitialScan
    // does not go through that codepath
    if (file.getFileName().toString().equals(".gitignore")) {
      b.setIgnoreString(FileUtils.readFileToString(file.toFile()));
    }
    put(queue, b.build());
  }

  private static void put(BlockingQueue<Update> queue, Update update) {
    if (log.isTraceEnabled()) {
      log.trace("  PUT: " + TextFormat.shortDebugString(update));
    }
    Utils.resetIfInterrupted(() -> {
      queue.put(update);
    });
  }

  private String toRelativePath(Path path) {
    return rootDirectory.relativize(path).toString().replace(File.separator, "/");
  }

  private static long lastModified(Path path) throws IOException {
    return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
  }

  /**
   * Does a best-effort at ensuring write events have finished before passing them
   * off to the incomingQueue.
   *
   * We perform this on it's own dedicated thread, so that the FileWatcher thread
   * can keep immediately grabbing updates from the watcher service, to help prevent
   *
   * We used to check writes after diffing, but doing it here means that the UpdateTree
   * will have all of the right metadata. (The con is that we'll debounce writes that
   * are for files that end up being ignored anyway; in theory the best place to
   * debounce + (eventually) read in digest is post-ignore logic, so somewhere in
   * the SyncLogic thread.
   *
   * Having the "wait for writes to settle down" done up-front also matches what
   * watchman does.
   */
  private class Debouncer implements TaskLogic {
    @Override
    public Duration runOneLoop() throws InterruptedException {
      Update u = rawUpdates.take();
      if (!u.getDirectory() && !u.getDelete() && u.getSymlink().isEmpty()) {
        // this is a file
        Utils.ensureSettled(fileAccess, rootDirectory.resolve(u.getPath()));
      } else if (u.getDelete()) {
        // would be nice to sleep on a delete, but if we have N deletes,
        // we don't want to naively sleep for N * 500ms.
      }
      queue.put(u);
      return null;
    }
  }

}

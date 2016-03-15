package mirror;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Recursively watches a directory for changes and sends them to a BlockingQueue for processing.
 *
 * Specifically, we generate Update events for:
 *
 * 1. File create, modify, delete
 * 2. Directory delete
 *
 * The idea being that directory creates aren't important until a file exists within them,
 * but directories deletes are important, because a dir1/ deletion means we need to remove
 * any files nested under dir1/.
 *
 * All of the events that we fire should use paths relative to {@code rootDirectory},
 * e.g. if we're watching {@code /home/user/code/}, and {@code project-a/foo.txt changes},
 * the path * of the event should be {@code project-a/foo.txt}.
 */
class FileWatcher {

  private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);
  private final Path rootDirectory;
  private final BlockingQueue<Update> queue;
  private final WatchService watchService;
  private final Predicate<Path> excludeFilter;

  public FileWatcher(WatchService watchService, Path rootDirectory, BlockingQueue<Update> queue, Predicate<Path> excludeFilter) {
    this.watchService = watchService;
    this.rootDirectory = rootDirectory;
    this.queue = queue;
    this.excludeFilter = excludeFilter;
  }

  /**
   * Initializes watches on the rootDirectory, and returns a list of all of
   * the file paths found while setting up listening hooks.
   *
   * This scan is performed on-thread and so this method blocks until complete.
   */
  public List<Update> performInitialScan() throws IOException, InterruptedException {
    onNewDirectory(rootDirectory);
    List<Update> updates = new ArrayList<>(queue.size());
    queue.drainTo(updates);
    return updates;
  }

  /**
   * Starts polling for changes.
   *
   * Polling happens on a separate thread, so this method does not block.
   */
  public void startPolling() throws IOException, InterruptedException {
    Runnable runnable = () -> {
      try {
        watchLoop();
      } catch (Exception e) {
        // TODO need to signal that our connection needs reset
        throw new RuntimeException(e);
      }
    };
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("FileWatcher-%s").build().newThread(runnable).start();
  }

  private void watchLoop() throws IOException, InterruptedException {
    while (true) {
      WatchKey watchKey = watchService.take();
      Path parentDir = (Path) watchKey.watchable();
      for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
        WatchEvent.Kind<?> eventKind = watchEvent.kind();
        if (eventKind == OVERFLOW) {
          throw new RuntimeException("Overflow");
        }
        Path child = parentDir.resolve((Path) watchEvent.context());
        if (eventKind == ENTRY_CREATE || eventKind == ENTRY_MODIFY) {
          onChangedPath(child);
        } else if (eventKind == ENTRY_DELETE) {
          onRemovedPath(child);
        }
      }
      watchKey.reset();
    }
  }

  private void onChangedPath(Path path) throws IOException, InterruptedException {
    // always recurse into directories so that even if we're excluding target/*,
    // if we are including target/scala-2.10/src_managed, then we can match those
    // paths even though we're ignoring some of the cruft around it
    if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
      onNewDirectory(path);
    } else {
      if (!excludeFilter.test(path)) {
        if (Files.isSymbolicLink(path)) {
          onChangedSymbolicLink(path);
        } else {
          onChangedFile(path);
        }
      }
    }
  }

  private void onRemovedPath(Path path) throws InterruptedException {
    String relativePath = toRelativePath(path);
    queue.put(Update.newBuilder().setPath(relativePath).setDelete(true).setLocal(true).build());
  }

  private void onNewDirectory(Path directory) throws IOException, InterruptedException {
    directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path child : stream) {
        onChangedPath(child);
      }
    }
  }

  private void onChangedFile(Path file) throws InterruptedException {
    String relativePath = toRelativePath(file);
    queue.put(Update.newBuilder().setPath(relativePath).setLocal(true).build());
  }

  private void onChangedSymbolicLink(Path path) throws IOException, InterruptedException {
    Path symlink = Files.readSymbolicLink(path);
    String targetPath;
    if (symlink.isAbsolute()) {
      targetPath = path.getParent().toAbsolutePath().relativize(symlink).toString();
    } else {
      // the symlink is already relative, so we can leave it alone, e.g. foo.txt
      targetPath = symlink.toString();
    }
    String relativePath = toRelativePath(path);
    log.debug("Symlink changed {}, relative={}, target={}", path, relativePath, targetPath);
    queue.put(Update.newBuilder().setPath(relativePath).setSymlink(targetPath).setLocal(true).build());
  }

  private String toRelativePath(Path path) {
    return rootDirectory.relativize(path).toString().replace(File.separator, "/");
  }

}

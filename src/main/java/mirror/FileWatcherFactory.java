package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mirror.tasks.TaskFactory;
import mirror.watchman.WatchmanChannelImpl;
import mirror.watchman.WatchmanFactory;
import mirror.watchman.WatchmanFileWatcher;

/**
 * Provides a factory for creating file watchers, given we need to
 * know the root directory for the watcher when we instantiate it.
 *
 * (E.g. we cannot instantiate the watcher just once at JVM once
 * start and then re-use it.)
 *
 * This also abstracts out whether we use the {@link WatchmanFileWatcher}
 * or the {@link WatchServiceFileWatcher}, with a preference for the
 * former.
 */
public interface FileWatcherFactory {

  FileWatcher newWatcher(Path root, BlockingQueue<Update> incomingQueue);

  /**
   * @return the default factory that will try to create a watchman-based impl if possible, otherwise a Java WatchService-based impl.
   */
  static FileWatcherFactory newFactory(TaskFactory taskFactory) {
    Logger log = LoggerFactory.getLogger(FileWatcherFactory.class);
    return (root, queue) -> {
      Optional<WatchmanFactory> wm = WatchmanChannelImpl.createIfAvailable();
      if (wm.isPresent()) {
        return new WatchmanFileWatcher(wm.get(), root, queue);
      } else {
        log.info("Watchman not found, using WatchService instead");
        log.info("  Note that WatchService is buggy on Linux, and uses polling on Mac.");
        log.info("  While mirror will work with WatchService, especially to test, you should eventually install watchman.");
        try {
          return new WatchServiceFileWatcher(taskFactory, FileSystems.getDefault().newWatchService(), root, queue);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

}

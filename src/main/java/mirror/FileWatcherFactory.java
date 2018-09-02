package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mirror.tasks.TaskFactory;
import mirror.watchman.Watchman;
import mirror.watchman.WatchmanFileWatcher;
import mirror.watchman.WatchmanImpl;

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

  FileWatcher newWatcher(MirrorPaths config, BlockingQueue<Update> incomingQueue);

  /**
   * @return the default factory that will try to create a watchman-based impl if possible, otherwise a Java WatchService-based impl.
   */
  static FileWatcherFactory newFactory(TaskFactory taskFactory) {
    Logger log = LoggerFactory.getLogger(FileWatcherFactory.class);
    return (config, queue) -> {
      Optional<Watchman> wm = WatchmanImpl.createIfAvailable();
      if (wm.isPresent()) {
        return new WatchmanFileWatcher(wm.get(), config, queue);
      } else {
        log.info("Watchman not found, using WatchService instead");
        log.info("  Note that WatchService is buggy on Linux, and uses polling on Mac.");
        log.info("  While mirror will work with WatchService, especially to test, you should eventually install watchman.");
        try {
          return new WatchServiceFileWatcher(taskFactory, FileSystems.getDefault().newWatchService(), config.root, queue);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

}

package mirror;

import mirror.tasks.TaskFactory;
import mirror.watchman.Watchman;
import mirror.watchman.WatchmanFileWatcher;
import mirror.watchman.WatchmanImpl;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;

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
    return (config, queue) -> {
      Optional<Watchman> wm = WatchmanImpl.createIfAvailable();
      if (wm.isPresent()) {
        return new WatchmanFileWatcher(wm.get(), config, queue);
      } else {
        throw new RuntimeException("Watchman not found, please install watchman and ensure its on your PATH.");
      }
    };
  }

}

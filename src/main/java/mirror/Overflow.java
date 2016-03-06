package mirror;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

public class Overflow {

  public static void main(final String[] args) throws InterruptedException, IOException {
    final int nfiles;
    if (args.length > 0) {
      nfiles = Integer.parseInt(args[0]);
    } else {
      nfiles = 10_000;
    }

    final Path directory = Files.createTempDirectory("watch-service-overflow");
    final WatchService watchService = FileSystems.getDefault().newWatchService();
    directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);

    final AtomicLong events = new AtomicLong();
    final CountDownLatch w = new CountDownLatch(1);

    new Thread() {
      public void run() {
        try {
          System.out.println("Watching");
          w.countDown();
          while (true) {
            WatchKey watchKey = watchService.take();
            if (watchKey == null) {
              System.out.println("watchKey == null");
              return;
            }
            // Path parentDir = (Path) watchKey.watchable();
            for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
              WatchEvent.Kind<?> eventKind = watchEvent.kind();
              if (eventKind == OVERFLOW) {
                System.out.println("Overflow, only got " + events.get());
                return;
              }
              // Path child = parentDir.resolve((Path) watchEvent.context());
              if (eventKind == ENTRY_CREATE) {
                events.incrementAndGet();
              } else if (eventKind == ENTRY_DELETE) {
                events.incrementAndGet();
              }
            }
            watchKey.reset();
          }
        } catch (InterruptedException ie) {
        }
      }
    }.start();
    w.await();

    System.out.println("Creating " + nfiles);
    final Path p = directory.resolve(Paths.get("food"));
    for (int i = 0; i < nfiles; i++) {
      Files.createFile(p);
      Files.delete(p);
    }

    Thread.sleep(1000);

    /*
    List<WatchEvent<?>> events = watchService.take().pollEvents();
    for (final WatchEvent<?> event : events) {
      if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
        System.out.println("Overflow.");
        System.out.println("Number of events: " + events.size());
        return;
      }
    }
    */

    System.out.println("No overflow, saw " + events.get());
    Files.delete(directory);
  }
}

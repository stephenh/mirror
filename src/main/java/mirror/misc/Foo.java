package mirror.misc;

import static com.google.protobuf.TextFormat.shortDebugString;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import mirror.FileWatcher;
import mirror.Update;
import mirror.WatchServiceFileWatcher;

public class Foo {

  @SuppressWarnings("serial")
  public static void main(String[] args) throws Exception {
    Path root = Paths.get("/home/stephen/dir1");
    FileWatcher f = new WatchServiceFileWatcher(FileSystems.getDefault().newWatchService(), root);
    BlockingQueue<Update> queue = new LinkedBlockingQueue<Update>(1000) {
      @Override
      public void put(Update u) throws InterruptedException {
        System.out.println("PUT " + shortDebugString(u));
        super.put(u);
      }
    };
    f.performInitialScan(queue);
    f.start(() -> System.out.println("done"));
    System.in.read();
  }
}

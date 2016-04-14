package mirror.misc;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Foo {

  public static void main(String[] args) throws Exception {
    Path p = Paths.get("/home/stephen/linkedin/tscp-admin-frontend/tscp-admin-frontend/public/sharebox-static");
    System.out.println(Files.isSymbolicLink(p));
    System.out.println(Files.exists(p));
    System.out.println(Files.exists(p, LinkOption.NOFOLLOW_LINKS));
    System.out.println(Files.readSymbolicLink(p));

    /*
    Path root = Paths.get("/home/stephen/dir1");
    FileWatcher f = new FileWatcher(FileSystems.getDefault().newWatchService(), root, new LinkedBlockingQueue<Update>() {
      @Override
      public void put(Update u) throws InterruptedException {
        System.out.println("PUT " + u);
        super.put(u);
      }
    });
    f.performInitialScan();
    f.startWatching();
    System.in.read();
    */
  }
}

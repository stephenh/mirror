package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.io.FileUtils;
import org.jooq.lambda.Seq;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import joist.util.Execute;

public class FileWatcherTest {

  private static final File dir = new File("./build/FileWatcherTest");
  private final BlockingQueue<Update> queue = new ArrayBlockingQueue<>(100);
  private FileWatcher watcher;

  @Before
  public void clearFiles() throws Exception {
    LoggingConfig.init();
    FileUtils.deleteDirectory(dir);
    dir.mkdirs();
    watcher = new FileWatcher(FileSystems.getDefault().newWatchService(), dir.toPath(), queue);
    watcher.performInitialScan();
    watcher.startWatching();
  }

  @After
  public void stopWatcher() throws Exception {
    watcher.stop();
  }

  @Test
  public void testFileRename() throws Exception {
    // given a directory is created
    File dir1 = new File(dir, "dir1");
    dir1.mkdir();
    // and then renamed
    File dir2 = new File(dir, "dir2");
    new Execute(new String[] { "mv", dir1.toString(), dir2.toString() }).toSystemOut();
    FileUtils.writeStringToFile(new File(dir2, "foo.txt"), "abc");
    sleep();
    assertThat( //
      Seq.seq(drainUpdates()).map(u -> u.getPath()).toString(","),
      // create dir1, delete dir1, create dir2, create foo.txt, modify foo.txt
      is("dir1,dir1,dir2,dir2/foo.txt,dir2/foo.txt"));
  }

  private List<Update> drainUpdates() {
    List<Update> list = new ArrayList<>();
    queue.drainTo(list);
    return list;
  }

  private static void sleep() throws InterruptedException {
    Thread.sleep(1500);
  }

}

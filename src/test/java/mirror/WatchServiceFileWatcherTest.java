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
import org.junit.Ignore;
import org.junit.Test;

import joist.util.Execute;
import mirror.tasks.TaskFactory;
import mirror.tasks.ThreadBasedTaskFactory;

/**
 * Tests {@link WatchServiceFileWatcher}.
 *
 * Currently ignored because when files are created, e.g. foo.txt,
 * {@link WatchServiceFileWatcher} will sometimes send one update
 * (create) and sometimes send two.
 * 
 * This is just what the underlying inotify events are, so it is not
 * wrong, but it makes test assertions annoying.
 */
@Ignore
public class WatchServiceFileWatcherTest {

  static {
    LoggingConfig.init();
  }
  private static final File dir = new File("./build/FileWatcherTest");
  private final TaskFactory taskFactory = new ThreadBasedTaskFactory();
  private final BlockingQueue<Update> queue = new ArrayBlockingQueue<>(100);
  private FileWatcher watcher;

  @Before
  public void clearFiles() throws Exception {
    if (dir.exists()) {
      FileUtils.forceDelete(dir);
    }
    dir.mkdirs();
    watcher = new WatchServiceFileWatcher(taskFactory, FileSystems.getDefault().newWatchService(), dir.toPath(), queue);
    watcher.performInitialScan();
    taskFactory.runTask(watcher);
  }

  @After
  public void stopWatcher() throws Exception {
    taskFactory.stopTask(watcher);
  }

  @Test
  public void testDirectoryRename() throws Exception {
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

  @Test
  public void testDirectoryRenamedWithNestedContents() throws Exception {
    // given a structure like:
    // dir1
    //   dir12
    //     foo.txt
    File dir1 = new File(dir, "dir1");
    dir1.mkdir();
    File dir12 = new File(dir1, "dir12");
    dir12.mkdir();
    File foo = new File(dir12, "foo.txt");
    FileUtils.writeStringToFile(foo, "abc");
    // when dir1 is renamed
    File dir2 = new File(dir, "dir2");
    new Execute(new String[] { "mv", dir1.toString(), dir2.toString() }).toSystemOut();
    // and foo.txt is written to
    FileUtils.writeStringToFile(new File(dir2, "dir12/foo.txt"), "abcd");
    sleep();
    // then we see:
    assertThat(
      Seq.seq(drainUpdates()).map(u -> u.getPath()).toString(","),
      is(String.join( //
        ",",
        "dir1", // create
        "dir1/dir12", // create
        "dir1/dir12/foo.txt", // create
        "dir1/dir12/foo.txt", // write
        "dir1", // delete
        "dir2", // create
        "dir2/dir12", // create
        "dir2/dir12/foo.txt", // create?
        "dir2/dir12/foo.txt"))); // update
  }

  @Test
  public void testDirectoryRecreated() throws Exception {
    // given a directory is created
    File dir1 = new File(dir, "dir1");
    dir1.mkdir();
    // and then deleted
    dir1.delete();
    // and then created again
    dir1.mkdir();
    sleep();
    // and we write a file inside of dir1
    FileUtils.writeStringToFile(new File(dir1, "foo.txt"), "abc");
    sleep();
    // then we see all of the events
    assertThat( //
      Seq.seq(drainUpdates()).map(u -> u.getPath()).toString(","),
      is("dir1,dir1,dir1,dir1/foo.txt,dir1/foo.txt"));
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

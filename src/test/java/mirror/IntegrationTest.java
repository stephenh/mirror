package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Channel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.ServerImpl;
import mirror.MirrorGrpc.MirrorStub;
import mirror.tasks.ThreadBasedTaskFactory;

public class IntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(IntegrationTest.class);
  private static final File integrationTestDir = new File("./build/IntergrationTest");
  private static final File root1 = new File(integrationTestDir, "root1");
  private static final File root2 = new File(integrationTestDir, "root2");
  private static int nextPort = 10_000;
  private ServerImpl rpc;
  private MirrorClient client;

  @Before
  public void clearFiles() throws Exception {
    LoggingConfig.init();
    FileUtils.deleteDirectory(integrationTestDir);
    integrationTestDir.mkdirs();
    root1.mkdirs();
    root2.mkdirs();
  }

  @After
  public void shutdown() throws Exception {
    // rpc.awaitTermination();
    if (client != null) {
      log.info("stopping client");
      client.stop();
    }
    if (rpc != null) {
      log.info("stopping server");
      rpc.shutdown();
    }
    rpc.shutdown();
    rpc.awaitTermination();
  }

  @Test
  public void testSimpleFile() throws Exception {
    startMirror();
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    sleep();
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is("abc"));
  }

  @Test
  public void testSimpleDirectory() throws Exception {
    startMirror();
    new File(root1, "foo").mkdir();
    sleep();
    assertThat(new File(root2, "foo").exists(), is(true));
    assertThat(new File(root2, "foo").isDirectory(), is(true));
  }

  @Test
  public void testDeleteSimpleFile() throws Exception {
    // given a file that exists in both root1/root2
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abc");
    startMirror();
    // when one file is deleted
    new File(root1, "foo.txt").delete();
    sleep();
    // then it's also deleted remotely
    assertThat(new File(root2, "foo.txt").exists(), is(false));
  }

  @Test
  public void testDeleteDirectory() throws Exception {
    // given a file within a directory that exists in both root1/root2
    for (File root : new File[] { root1, root2 }) {
      new File(root, "dir").mkdir();
      FileUtils.writeStringToFile(new File(root, "dir/foo.txt"), "abc");
    }
    startMirror();
    // when the directory is deleted
    FileUtils.deleteDirectory(new File(root1, "dir"));
    sleep();
    // then the child was also deleted
    assertThat(new File(root2, "dir/foo.txt").exists(), is(false));
  }

  @Test
  public void testCreateNestedFile() throws Exception {
    startMirror();
    // given a file that is created in a sub directory
    new File(root1, "dir").mkdir();
    FileUtils.writeStringToFile(new File(root1, "dir/foo.txt"), "abc");
    sleep();
    // then it's copied remotely
    assertThat(FileUtils.readFileToString(new File(root2, "dir/foo.txt")), is("abc"));
  }

  @Test
  public void testTwoWay() throws Exception {
    startMirror();
    // given a root1 change
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    sleep();
    // and it is replicated to root2
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is("abc"));
    // and it then changes on root2
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abcd");
    sleep();
    // then it is also replicated back to root1
    assertThat(FileUtils.readFileToString(new File(root1, "foo.txt")), is("abcd"));
  }

  @Test
  public void testSeveralFilesFromServerToClient() throws Exception {
    startMirror();
    // given a root1 change
    int i = 0;
    for (; i < 100; i++) {
      FileUtils.writeStringToFile(new File(root2, "foo" + i + ".txt"), "abc");
    }
    sleep();
    sleep();
    // and it is replicated to root2
    assertThat(FileUtils.readFileToString(new File(root1, "foo0.txt")), is("abc"));
    assertThat(FileUtils.readFileToString(new File(root1, "foo" + (i - 1) + ".txt")), is("abc"));
  }

  @Test
  public void testFileSymlinks() throws Exception {
    // given a file that exists in both remotes
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abc");
    startMirror();
    // when a symlink is created on root1
    Files.createSymbolicLink(root1.toPath().resolve("foo2"), Paths.get("foo.txt"));
    sleep();
    // then it is replicated to root2 as a symlink
    assertThat(Files.readSymbolicLink(root2.toPath().resolve("foo2")).toString(), is("foo.txt"));
    assertThat(FileUtils.readFileToString(new File(root2, "foo2")), is("abc"));
  }

  @Test
  public void testFileSymlinksThatAreUpdatedAfterInitialSync() throws Exception {
    // given a file that exists in both remotes, and has a symlink to it
    for (File root : new File[] { root1, root2 }) {
      FileUtils.writeStringToFile(new File(root, "foo1.txt"), "abc1");
      FileUtils.writeStringToFile(new File(root, "foo2.txt"), "abc2");
      Files.createSymbolicLink(new File(root, "foo").toPath(), Paths.get("foo1.txt"));
    }
    sleep();
    startMirror();
    sleep();
    // when the symlink is updated on root1
    new File(root1, "foo").delete();
    Files.createSymbolicLink(root1.toPath().resolve("foo"), Paths.get("foo2.txt"));
    sleep();
    // then it is replicated to root2 as a symlink
    assertThat(Files.readSymbolicLink(root2.toPath().resolve("foo")).toString(), is("foo2.txt"));
    assertThat(FileUtils.readFileToString(new File(root2, "foo")), is("abc2"));
  }

  @Test
  public void testFileSymlinksThatAreUpdatedDuringInitialSync() throws Exception {
    // given a file that exists in both remotes, and has a symlink to it
    for (File root : new File[] { root1, root2 }) {
      FileUtils.writeStringToFile(new File(root, "foo1.txt"), "abc1");
      FileUtils.writeStringToFile(new File(root, "foo2.txt"), "abc2");
    }
    // and root1 has a symlink to foo1
    Files.createSymbolicLink(new File(root1, "foo").toPath(), Paths.get("foo1.txt"));
    // and root2 has a symlink to foo2 which is newer
    Thread.sleep(1000);
    Files.createSymbolicLink(new File(root2, "foo").toPath(), Paths.get("foo2.txt"));
    // when we start
    startMirror();
    sleep();
    // then we update the link on root1
    assertThat(Files.readSymbolicLink(root1.toPath().resolve("foo")).toString(), is("foo2.txt"));
  }

  @Test
  public void testFileSymlinksThatAreAbsolutePaths() throws Exception {
    // given a file that exists in both remotes
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abc");
    startMirror();
    // when a symlink is created on root1
    Files.createSymbolicLink(root1.toPath().resolve("foo2"), root1.toPath().resolve("foo.txt").toAbsolutePath());
    sleep();
    // then it is replicated to root2 as a symlink
    assertThat(Files.readSymbolicLink(root2.toPath().resolve("foo2")).toString(), is("foo.txt"));
    assertThat(FileUtils.readFileToString(new File(root2, "foo2")), is("abc"));
  }

  @Test
  public void testFileSymlinksToADifferentDirectory() throws Exception {
    // given a file in a/ that exists in both remotes
    for (File root : new File[] { root1, root2 }) {
      new File(root, "a").mkdir();
      new File(root, "b").mkdir();
      FileUtils.writeStringToFile(new File(root, "a/foo.txt"), "abc");
    }
    startMirror();
    // when a symlink to b/ is created on root1
    Files.createSymbolicLink(root1.toPath().resolve("b/foo2"), Paths.get("../a/foo.txt"));
    sleep();
    // then it is replicated to root2 as a symlink
    assertThat(Files.readSymbolicLink(root2.toPath().resolve("b/foo2")).toString(), is("../a/foo.txt"));
    assertThat(FileUtils.readFileToString(new File(root2, "b/foo2")), is("abc"));
  }

  @Test
  public void testDirectorySymlinks() throws Exception {
    // given a file that exists in both remotes
    FileUtils.writeStringToFile(new File(root1, "a/foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "a/foo.txt"), "abc");
    startMirror();
    // when a symlink for it's directory is created on root1
    Files.createSymbolicLink(root1.toPath().resolve("b"), Paths.get("a"));
    sleep();
    // then it is replicated to root2 as a symlink
    assertThat(Files.readSymbolicLink(root2.toPath().resolve("b")).toString(), is("a"));
    assertThat(FileUtils.readFileToString(new File(root2, "b/foo.txt")), is("abc"));
  }

  @Test
  public void testInitialSyncDirectorySymlinkFromServerToClient() throws Exception {
    // given a file that exists in both remotes
    FileUtils.writeStringToFile(new File(root1, "a/foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "a/foo.txt"), "abc");
    // and it is also symlinked on root1
    new File(root1, "b").mkdir();
    Files.createSymbolicLink(new File(root1, "b/foo.txt").toPath(), new File(root1, "a/foo.txt").getAbsoluteFile().toPath());
    // when mirror is started
    startMirror();
    sleep();
    // then the symlink is replicated to root2
    assertThat(Files.readSymbolicLink(root2.toPath().resolve("b/foo.txt")).toString(), is("../a/foo.txt"));
    assertThat(FileUtils.readFileToString(new File(root2, "b/foo.txt")), is("abc"));
  }

  @Test
  public void testInitialSyncDirectorySymlinkFromClientToServer() throws Exception {
    // given a file that exists in both remotes
    FileUtils.writeStringToFile(new File(root1, "a/foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "a/foo.txt"), "abc");
    // and it is also symlinked on root2
    Files.createSymbolicLink(new File(root2, "b").toPath(), new File(root2, "a/foo.txt").getAbsoluteFile().toPath());
    // when mirror is started
    startMirror();
    sleep();
    // then the symlink is replicated to root1
    assertThat(Files.readSymbolicLink(root1.toPath().resolve("b")).toString(), is("a/foo.txt"));
    assertThat(FileUtils.readFileToString(new File(root1, "b")), is("abc"));
  }

  @Test
  public void testInitialSyncMissingFileFromServerToClient() throws Exception {
    // given root1 has an existing file
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    // when mirror is started
    startMirror();
    sleep();
    // then the file is created in root2
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is("abc"));
  }

  @Test
  public void testInitialSyncMissingFileFromClientToServer() throws Exception {
    // given root2 has an existing file
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abc");
    // when mirror is started
    startMirror();
    sleep();
    // then the file is created in root1
    assertThat(FileUtils.readFileToString(new File(root1, "foo.txt")), is("abc"));
  }

  @Test
  public void testInitialSyncStaleFileFromServerToClient() throws Exception {
    // given both roots have an existing file
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abcd");
    // and root1's file is newer
    new File(root1, "foo.txt").setLastModified(2000);
    new File(root2, "foo.txt").setLastModified(1000);
    // when mirror is started
    startMirror();
    sleep();
    // then the file is updated in root2
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is("abc"));
  }

  @Test
  public void testInitialSyncStaleFileFromClientToServer() throws Exception {
    // given both roots have an existing file
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abcd");
    // and root2's file is newer
    new File(root1, "foo.txt").setLastModified(2000);
    new File(root2, "foo.txt").setLastModified(3000);
    // when mirror is started
    startMirror();
    sleep();
    // then the file is updated in root1
    assertThat(FileUtils.readFileToString(new File(root1, "foo.txt")), is("abcd"));
  }

  @Test
  public void testSkipIgnoredDirectories() throws Exception {
    // given a file that exists within an ignored directory
    FileUtils.writeStringToFile(new File(root1, "target/foo.txt"), "abc");
    // when mirror is started
    startMirror();
    sleep();
    // then it is not replicated to root2
    assertThat(new File(root2, "target/foo.txt").exists(), is(false));
  }

  @Test
  public void testIncludeWithinExcludedDirectories() throws Exception {
    // given a file that exists within an included directory
    FileUtils.writeStringToFile(new File(root1, "tmp/src_managed/foo.txt"), "abc");
    // when mirror is started
    startMirror();
    sleep();
    // then it is replicated to root2
    assertThat(new File(root2, "tmp/src_managed/foo.txt").exists(), is(true));
  }

  @Test
  public void testIncludeWithPatternWithinExcludedDirectories() throws Exception {
    // given a file that exists within an included directory
    FileUtils.writeStringToFile(new File(root1, "target/foo/includedDirectory/foo.txt"), "abc");
    // when mirror is started
    startMirror();
    sleep();
    // then it is replicated to root2
    assertThat(new File(root2, "target/foo/includedDirectory/foo.txt").exists(), is(true));
  }

  @Test
  public void testDontRecurseIntoSymlink() throws Exception {
    // given that both roots have a symlink from src to target
    Files.createSymbolicLink(root1.toPath().resolve("src"), Paths.get("target"));
    // and target only exists on root1
    new File(root1, "target").mkdirs();
    FileUtils.writeStringToFile(new File(root1, "target/output"), "output");
    // when mirror is started
    startMirror();
    sleep();
    // then the symlink on root1 works
    assertThat(FileUtils.readFileToString(new File(root1, "src/output")), is("output"));
    // but we didn't copy it over to root2
    assertThat(new File(root2, "src/output").exists(), is(false));
  }

  @Test
  public void testSymlinkThatIsNowARealPath() throws Exception {
    // given that root2 thought src was a symlink
    Files.createSymbolicLink(root2.toPath().resolve("src"), Paths.get("target"));
    NativeFileAccess.setModifiedTimeForSymlink(root2.toPath().resolve("src"), 1000);
    // but now on root1 it's actually a real directory
    new File(root1, "src").mkdir();
    FileUtils.writeStringToFile(new File(root1, "src/foo.txt"), "foo");
    new File(root1, "src/foo.txt").setLastModified(2000);
    // when mirror is started
    startMirror();
    sleep();
    // then root2's src directory is not a real path
    assertThat(Files.isSymbolicLink(root2.toPath().resolve("src")), is(false));
    // and root1 stays a real patha s well
    assertThat(Files.isSymbolicLink(root1.toPath().resolve("src")), is(false));
  }

  @Test
  public void testRealPathThatIsNowASymlink() throws Exception {
    // given that root2 thought src was a real path
    new File(root2, "src").mkdir();
    FileUtils.writeStringToFile(new File(root2, "src/foo.txt"), "foo");
    sleep();
    // but now it's a symlink
    Files.createSymbolicLink(root1.toPath().resolve("src"), Paths.get("target"));
    // when mirror is started
    startMirror();
    sleep();
    // then root2's src directory is not a real path
    assertThat(Files.isSymbolicLink(root2.toPath().resolve("src")), is(true));
  }

  @Test
  public void testRespectsGitIgnoreFile() throws Exception {
    // given that root1 has a .gitignore file
    FileUtils.writeStringToFile(new File(root1, ".gitignore"), "foo.txt");
    // and a file that would be ignored
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "foo");
    // when mirror is started
    startMirror();
    sleep();
    // then we don't sync it
    assertThat(new File(root2, "foo.txt").exists(), is(false));
  }

  @Test
  public void testRespectsGitIgnoreFileInNestedDirectories() throws Exception {
    // given that root1 has a .gitignore file
    FileUtils.writeStringToFile(new File(root1, "foo/.gitignore"), "dir1/*");
    // and a file that would be ignored
    FileUtils.writeStringToFile(new File(root1, "foo/dir1/foo.txt"), "foo");
    // when mirror is started
    startMirror();
    sleep();
    // then we don't sync it
    assertThat(new File(root2, "foo/dir1/foo.txt").exists(), is(false));
  }

  @Test
  public void testUpdateFileThatWasMarkedReadOnlyByCodeGenerator() throws Exception {
    // given two files exist
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc1");
    FileUtils.writeStringToFile(new File(root2, "foo.txt"), "abc2");
    // and root1's file is newer
    new File(root1, "foo.txt").setLastModified(2000);
    new File(root2, "foo.txt").setLastModified(1000);
    // but root2's file is read only (e.g. due to overzealous code generators marking it read-only)
    NativeFileAccess.setReadOnly(new File(root2, "foo.txt").toPath());
    // when mirror is started
    startMirror();
    sleep();
    // then we can successfully update root2
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is("abc1"));
  }

  @Test
  public void testSimpleFileThatIsEmpty() throws Exception {
    startMirror();
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "");
    sleep();
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is(""));
  }

  private void startMirror() throws Exception {
    // server
    int port = nextPort++;
    // rpc = NettyServerBuilder.forPort(port).addService(MirrorServer.createWithCompressionEnabled()).build();
    rpc = InProcessServerBuilder.forName("mirror" + port).addService(new MirrorServer()).build();
    rpc.start();
    log.info("started server");
    // client
    PathRules includes = new PathRules("includedDirectory");
    PathRules excludes = new PathRules("target/");
    // Channel c = NettyChannelBuilder.forAddress("localhost", port).negotiationType(NegotiationType.PLAINTEXT).build();
    Channel c = InProcessChannelBuilder.forName("mirror" + port).build();
    MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");
    client = new MirrorClient(// 
      root2.toPath(),
      root1.toPath(),
      includes,
      excludes,
      new ThreadBasedTaskFactory(),
      new ConnectionDetector.Impl(),
      FileSystems.getDefault());
    client.startSession(stub);
    log.info("started client");
  }

  private static void sleep() throws InterruptedException {
    Thread.sleep(1500);
  }

}

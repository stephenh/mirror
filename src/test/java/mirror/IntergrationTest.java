package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.grpc.Channel;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import mirror.MirrorGrpc.MirrorStub;

public class IntergrationTest {

  private static final File integrationTestDir = new File("./build/IntergrationTest");
  private static final File root1 = new File(integrationTestDir, "root1");
  private static final File root2 = new File(integrationTestDir, "root2");
  private static int nextPort = 10_000;
  private ServerImpl rpc;
  private MirrorClient client;

  @Before
  public void clearFiles() throws Exception {
    FileUtils.deleteDirectory(integrationTestDir);
    integrationTestDir.mkdirs();
    root1.mkdirs();
    root2.mkdirs();
  }

  @After
  public void shutdown() throws Exception {
    // rpc.awaitTermination();
    if (rpc != null) {
      System.out.println("stopping server");
      rpc.shutdownNow();
    }
    if (client != null) {
      System.out.println("stopping client");
      client.stop();
    }
  }

  @Test
  public void testSimpleFile() throws Exception {
    startMirror();
    FileUtils.writeStringToFile(new File(root1, "foo.txt"), "abc");
    sleep();
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is("abc"));
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
    assertThat(FileUtils.readFileToString(new File(root2, "foo.txt")), is("abcd"));
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

  private void startMirror() throws Exception {
    // server
    int port = nextPort++;
    rpc = NettyServerBuilder.forPort(port).addService(MirrorGrpc.bindService(new MirrorServer(root1.toPath()))).build();
    rpc.start();
    System.out.println("started server");
    // client
    Channel c = NettyChannelBuilder.forAddress("localhost", port).negotiationType(NegotiationType.PLAINTEXT).build();
    MirrorStub stub = MirrorGrpc.newStub(c);
    client = new MirrorClient(root2.toPath());
    client.startSession(stub);
    System.out.println("started client");
  }

  private static void sleep() throws InterruptedException {
    Thread.sleep(500);
  }

}

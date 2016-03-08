package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;

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

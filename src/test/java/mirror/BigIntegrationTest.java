package mirror;

import static mirror.TestUtils.readFile;
import static mirror.TestUtils.writeFile;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.util.ArrayList;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import mirror.tasks.TaskFactory;
import mirror.tasks.ThreadBasedTaskFactory;

@Ignore
public class BigIntegrationTest {

  static {
    LoggingConfig.init();
  }

  private static final Logger log = LoggerFactory.getLogger(BigIntegrationTest.class);
  private static final File integrationTestDir = new File("./build/BigIntergrationTest");
  private static final File root1 = new File(integrationTestDir, "root1");
  private static final File root2 = new File(integrationTestDir, "root2");
  private static int nextPort = 10_000;
  private Server rpc;
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
  public void testLotsOfFiles() throws Exception {
    for (int i = 0; i < 500; i++) {
      for (int j = 0; j < 100; j++) {
        for (int k = 0; k < 10; k++) {
          writeFile(new File(root1, "project" + i + "/dir" + j + "/file-" + k + ".txt"), "abc");
        }
      }
    }
    startMirror();
    sleep();
    sleep();
    sleep();
    sleep();
    sleep();
    assertThat(readFile(new File(root2, "foo.txt")), is("abc"));
  }

  private void startMirror() throws Exception {
    // server
    int port = nextPort++;
    TaskFactory serverTaskFactory = new ThreadBasedTaskFactory();
    FileWatcherFactory watcherFactory = FileWatcherFactory.newFactory(serverTaskFactory);
    FileAccessFactory accessFactory = new NativeFileAccessFactory();
    MirrorServer server = new MirrorServer(serverTaskFactory, accessFactory, watcherFactory);
    // rpc = NettyServerBuilder.forPort(port).addService(server).build();
    rpc = InProcessServerBuilder.forName("mirror" + port).addService(server).build();
    rpc.start();
    log.info("started server");
    // client
    PathRules includes = new PathRules("includedDirectory");
    PathRules excludes = new PathRules("target/");
    // ChannelFactory cf = () -> NettyChannelBuilder.forAddress("localhost", port).negotiationType(NegotiationType.PLAINTEXT).build();
    ChannelFactory cf = () -> InProcessChannelBuilder.forName("mirror" + port).build();
    TaskFactory clientTaskFactory = new ThreadBasedTaskFactory();
    client = new MirrorClient(// 
      new MirrorPaths(root2.toPath(), root1.toPath(), includes, excludes, false, new ArrayList<>()),
      clientTaskFactory,
      new ConnectionDetector.Impl(cf),
      watcherFactory,
      new NativeFileAccess(root2.toPath().toAbsolutePath()),
      cf);
    client.startSession();
    log.info("started client");
  }

  private static void sleep() throws InterruptedException {
    Thread.sleep(1500);
  }

}

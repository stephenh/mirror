package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rvesse.airline.Cli;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;
import com.github.rvesse.airline.builder.CliBuilder;
import com.github.rvesse.airline.help.Help;

import io.grpc.Channel;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import mirror.MirrorGrpc.MirrorStub;
import mirror.tasks.ThreadBasedTaskFactory;

public class Mirror {

  private static final Logger log = LoggerFactory.getLogger(Mirror.class);
  private static final int defaultPort = 49172;

  static {
    LoggingConfig.init();
  }

  public static void main(String[] args) throws Exception {
    CliBuilder<Runnable> b = Cli.<Runnable> builder("mirror").withDescription("two-way, real-time sync of files across machines");
    b.withCommand(MirrorClientArgs.class);
    b.withCommand(MirrorServerArgs.class);
    b.withDefaultCommand(Help.class);
    b.build().parse(args).run();
  }

  @Command(name = "server", description = "starts a server for the remote client to connect to")
  public static class MirrorServerArgs implements Runnable {
    @Option(name = { "-p", "--post" }, description = "port to listen on, default: " + defaultPort)
    public int port = defaultPort;

    @Override
    public void run() {
      ServerImpl rpc = NettyServerBuilder.forPort(port).maxMessageSize(1073741824).addService(new MirrorServer()).build();
      try {
        rpc.start();
        log.info("Listening on " + port);
        rpc.awaitTermination();
      } catch (IOException e) {
        e.printStackTrace();
        System.exit(-1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Command(name = "client", description = "two-way real-time sync")
  public static class MirrorClientArgs implements Runnable {
    @Option(name = { "-h", "--host" }, description = "host name of remote server to connect to")
    public String host;

    @Option(name = { "-p", "--post" }, description = "port remote server to connect to, default: " + defaultPort)
    public int port = defaultPort;

    @Option(name = { "-l", "--local-root" }, description = "path on the local side to sync, e.g. ~/code")
    public String localRoot;

    @Option(name = { "-r", "--remote-root" }, description = "path on the remote side to sync, e.g. ~/code")
    public String remoteRoot;

    @Override
    public void run() {
      try {
        Channel c = NettyChannelBuilder.forAddress(host, port).negotiationType(NegotiationType.PLAINTEXT).maxMessageSize(1073741824).build();
        MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");
        MirrorClient client = new MirrorClient(//
          Paths.get(localRoot),
          Paths.get(remoteRoot),
          new ThreadBasedTaskFactory(),
          new ConnectionDetector.Impl(),
          FileSystems.getDefault());
        client.startSession(stub);
        // dumb way of waiting until they hit control-c
        CountDownLatch cl = new CountDownLatch(1);
        cl.await();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

}

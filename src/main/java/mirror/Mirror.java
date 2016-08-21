package mirror;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
  private static final int maxMessageSize = 1073741824; // 1gb
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
      ServerImpl rpc = NettyServerBuilder
        .forPort(port)
        .maxMessageSize(maxMessageSize)
        .addService(MirrorServer.createWithCompressionEnabled())
        .build();
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

    @Option(name = { "-i", "--include" }, description = "pattern of files to sync, even if they are git ignored")
    public List<String> extraIncludes = new ArrayList<>();

    @Option(name = { "-e", "--exclude" }, description = "pattern of files to skip, in addition to what is git ignored")
    public List<String> extraExcludes = new ArrayList<>();

    @Option(name = { "-li", "--use-internal-patterns" }, description = "use hardcoded include/excludes that generally work well for internal repos")
    public boolean useInternalPatterns;

    @Override
    public void run() {
      try {
        Channel c = NettyChannelBuilder.forAddress(host, port).negotiationType(NegotiationType.PLAINTEXT).maxMessageSize(maxMessageSize).build();
        MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");

        PathRules includes = new PathRules();
        PathRules excludes = new PathRules();

        addDefaultIncludeExcludeRules(includes, excludes);
        if (useInternalPatterns) {
          addInternalDefaults(includes, excludes);
        }
        extraIncludes.forEach(line -> includes.addRule(line));
        extraExcludes.forEach(line -> excludes.addRule(line));

        MirrorClient client = new MirrorClient(//
          Paths.get(localRoot),
          Paths.get(remoteRoot),
          includes,
          excludes,
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

  private static void addDefaultIncludeExcludeRules(PathRules includes, PathRules excludes) {
    // IntelliJ safe write files
    excludes.addRule("*___jb_bak___");
    excludes.addRule("*___jb_old___");
    // not sure why a .gitignore would be ignored?
    includes.addRule(".gitignore");
  }

  private static void addInternalDefaults(PathRules includes, PathRules excludes) {
    // Maybe most of these could be dropped if svn:ignore was supported?
    excludes.addRules("tmp", "temp", "target", "build", "bin", ".*");
    // these are resources in the build/ directory that are still useful to have
    // on the laptop, e.g. for the IDE
    includes.setRules(
      "**/src/mainGeneratedRest",
      "**/src/mainGeneratedDataTemplate",
      "testGeneratedRest",
      "testGeneratedDataTemplate",
      "**/build/*/classes/mainGeneratedInternalUrns/",
      "**/build/*/resources/mainGeneratedInternalUrns/",
      "src_managed",
      "*-SNAPSHOT.jar",
      "*.iml",
      "*.ipr",
      "*.iws",
      ".classpath",
      ".project",
      ".gitignore");
  }

}

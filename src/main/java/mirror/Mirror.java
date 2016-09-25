package mirror;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rvesse.airline.annotations.Cli;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;
import com.github.rvesse.airline.help.Help;

import io.grpc.Channel;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import mirror.Mirror.MirrorClientCommand;
import mirror.Mirror.MirrorServerCommand;
import mirror.MirrorGrpc.MirrorStub;
import mirror.tasks.TaskFactory;
import mirror.tasks.ThreadBasedTaskFactory;

@Cli(
  name = "mirror",
  description = "two-way, real-time sync of files across machines",
  commands = { MirrorClientCommand.class, MirrorServerCommand.class },
  defaultCommand = Help.class)
// @Version(sources = { "/META-INF/MANIFEST.MF" }, suppressOnError = false)
public class Mirror {

  private static final Logger log = LoggerFactory.getLogger(Mirror.class);
  private static final int maxMessageSize = 1073741824; // 1gb
  private static final int defaultPort = 49172;

  static {
    LoggingConfig.init();
  }

  public static void main(String[] args) throws Exception {
    com.github.rvesse.airline.Cli<Runnable> cli = new com.github.rvesse.airline.Cli<>(Mirror.class);
    cli.parse(args).run();
  }

  public static abstract class BaseCommand implements Runnable {
    @Option(name = "--skip-limit-checks", description = "skip system file descriptor/watches checks")
    public boolean skipLimitChecks;

    @Option(name = "--enable-log-file", description = "enables logging debug statements to mirror.log")
    public boolean enableLogFile;

    @Override
    public final void run() {
      if (enableLogFile) {
        LoggingConfig.enableLogFile();
      }
      if (!skipLimitChecks && !SystemChecks.checkLimits()) {
        // SystemChecks will have log.error'd some output
        System.exit(-1);
      }
      runIfChecksOkay();
    }

    protected abstract void runIfChecksOkay();
  }

  @Command(name = "server", description = "starts a server for the remote client to connect to")
  public static class MirrorServerCommand extends BaseCommand {
    @Option(name = { "-p", "--post" }, description = "port to listen on, default: " + defaultPort)
    public int port = defaultPort;

    @Override
    protected void runIfChecksOkay() {
      TaskFactory taskFactory = new ThreadBasedTaskFactory();
      FileWatcherFactory watcherFactory = FileWatcherFactory.newFactory(taskFactory);
      MirrorServer server = new MirrorServer(watcherFactory);

      ServerImpl rpc = NettyServerBuilder
        .forPort(port)
        .maxMessageSize(maxMessageSize)
        .addService(MirrorServer.withCompressionEnabled(server))
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
  public static class MirrorClientCommand extends BaseCommand {
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
    protected void runIfChecksOkay() {
      try {
        Channel c = NettyChannelBuilder.forAddress(host, port).negotiationType(NegotiationType.PLAINTEXT).maxMessageSize(maxMessageSize).build();
        MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");

        PathRules includes = new PathRules();
        PathRules excludes = new PathRules();
        setupIncludesAndExcludes(includes, excludes, extraIncludes, extraExcludes, useInternalPatterns);

        TaskFactory taskFactory = new ThreadBasedTaskFactory();
        FileWatcherFactory watcherFactory = FileWatcherFactory.newFactory(taskFactory);

        MirrorClient client = new MirrorClient(//
          Paths.get(localRoot),
          Paths.get(remoteRoot),
          includes,
          excludes,
          taskFactory,
          new ConnectionDetector.Impl(),
          watcherFactory);
        client.startSession(stub);
        // dumb way of waiting until they hit control-c
        CountDownLatch cl = new CountDownLatch(1);
        cl.await();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static void setupIncludesAndExcludes(
    PathRules includes,
    PathRules excludes,
    List<String> extraIncludes,
    List<String> extraExcludes,
    boolean useInternalPatterns) {
    addDefaultIncludeExcludeRules(includes, excludes);
    if (useInternalPatterns) {
      addInternalDefaults(includes, excludes);
    }
    extraIncludes.forEach(line -> includes.addRule(line));
    extraExcludes.forEach(line -> excludes.addRule(line));
  }

  private static void addDefaultIncludeExcludeRules(PathRules includes, PathRules excludes) {
    // IntelliJ safe write files
    excludes.addRule("*___jb_bak___");
    excludes.addRule("*___jb_old___");
    // Ignore all hidden files, e.g. especially .git/.svn directories
    excludes.addRule(".*");
    // Since we exclude hidden files, re-include .gitignore so the remote-side knows what to ignore
    includes.addRule(".gitignore");
  }

  private static void addInternalDefaults(PathRules includes, PathRules excludes) {
    // Maybe most of these could be dropped if svn:ignore was supported?
    excludes.addRules("tmp", "temp", "target", "build", "bin");
    // these are resources in the build/ directory that are still useful to have
    // on the laptop, e.g. for the IDE
    includes.setRules(
      // include generated source code
      "src_managed",
      "**/src/mainGeneratedRest",
      "**/src/mainGeneratedDataTemplate",
      "testGeneratedRest",
      "testGeneratedDataTemplate",
      "**/build/*/classes/mainGeneratedInternalUrns/",
      "**/build/*/resources/mainGeneratedInternalUrns/",
      // sync the MP-level config directory
      "*/config",
      // but not svn directories within it
      "!*/config/**/.svn",
      // include the binaries the laptop-side IDE will want
      "*-SNAPSHOT.jar",
      // include project files for the laptop-side IDE
      "*.iml",
      "*.ipr",
      "*.iws",
      ".classpath",
      ".project");
  }

}

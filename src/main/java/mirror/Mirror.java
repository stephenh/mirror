package mirror;

import static org.apache.commons.lang3.StringUtils.chomp;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rvesse.airline.annotations.Cli;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;
import com.github.rvesse.airline.help.Help;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;

import io.grpc.Server;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import mirror.Mirror.MirrorClientCommand;
import mirror.Mirror.MirrorServerCommand;
import mirror.Mirror.VersionCommand;
import mirror.tasks.TaskFactory;
import mirror.tasks.ThreadBasedTaskFactory;

@Cli(name = "mirror", description = "two-way, real-time sync of files across machines", commands = {
  MirrorClientCommand.class,
  MirrorServerCommand.class,
  VersionCommand.class }, defaultCommand = Help.class)
public class Mirror {

  private static final Logger log = LoggerFactory.getLogger(Mirror.class);
  private static final int maxMessageSize = 1073741824; // 1gb
  private static final int defaultPort = 49172;
  private static final String defaultHost = "0.0.0.0";
  private static final int keepAliveInSeconds = 20;
  private static final int keepAliveTimeoutInSeconds = 5;

  static {
    LoggingConfig.init();
  }

  public static void main(String[] args) throws Exception {
    com.github.rvesse.airline.Cli<Runnable> cli = new com.github.rvesse.airline.Cli<>(Mirror.class);
    cli.parse(args).run();
  }

  @Command(name = "version")
  public static class VersionCommand implements Runnable {
    @Override
    public void run() {
      String currentVersion = getVersion();
      System.out.println("Current Version: " + currentVersion);
      try {
        String latestVersion = chomp(Resources.asCharSource(new URL("http://repo.joist.ws/mirror-version"), Charsets.UTF_8).read());
        System.out.println("Latest Version: " + latestVersion);
        if (!currentVersion.equals(latestVersion)) {
          System.out.println("Comparison: https://github.com/stephenh/mirror/compare/" + toRef(currentVersion) + "..." + toRef(latestVersion));
        }
      } catch (Exception e) {
        log.error("Could not find latest version", e);
      }
    }

    private String toRef(String version) {
      if (version.contains("-g")) {
        return substringAfterLast(version, "-g").replace("-dirty", "");
      } else {
        return version;
      }
    }
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
    @Option(name = { "-p", "--port" }, description = "port to listen on, default: " + defaultPort)
    public int port = defaultPort;

    @Option(name = { "-h", "--host" }, description = "host name to listen on, default: " + defaultHost)
    public String host = defaultHost;

    @Override
    protected void runIfChecksOkay() {
      TaskFactory taskFactory = new ThreadBasedTaskFactory();
      FileAccessFactory accessFactory = new NativeFileAccessFactory();
      FileWatcherFactory watcherFactory = FileWatcherFactory.newFactory(taskFactory);
      MirrorServer server = new MirrorServer(taskFactory, accessFactory, watcherFactory);

      Server rpc = NettyServerBuilder
        .forAddress(new InetSocketAddress(host, port))
        .maxInboundMessageSize(maxMessageSize)
        .keepAliveTime(keepAliveInSeconds, TimeUnit.SECONDS)
        .keepAliveTimeout(keepAliveTimeoutInSeconds, TimeUnit.SECONDS)
        // add in /2 to whatever the client is sending to account for latency
        .permitKeepAliveTime(keepAliveInSeconds / 2, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(true)
        .intercept(new MirrorServer.EnableCompressionInterceptor())
        .addService(server)
        .build();

      try {
        rpc.start();
        log.info("Listening on " + host + ":" + port + ", version " + Mirror.getVersion());
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

    @Option(name = { "-p", "--port" }, description = "port remote server to connect to, default: " + defaultPort)
    public int port = defaultPort;

    @Option(name = { "-l", "--local-root" }, description = "path on the local side to sync, e.g. ./code; either absolute or relative to the directory mirror client was invoked in")
    public String localRoot;

    @Option(name = { "-r", "--remote-root" }, description = "path on the remote side to sync, e.g. ./code; either absolute or relative to the directory mirror server was invoked in")
    public String remoteRoot;

    @Option(name = { "-i", "--include" }, description = "pattern of files to sync, even if they are git ignored")
    public List<String> extraIncludes = new ArrayList<>();

    @Option(name = { "-e", "--exclude" }, description = "pattern of files to skip, in addition to what is git ignored")
    public List<String> extraExcludes = new ArrayList<>();

    @Option(name = { "-d", "--debug-all" }, description = "turn on debugging for all paths")
    public boolean debugAll = false;

    @Option(name = { "--debug-prefixes" }, description = "prefix of paths to print debug lines for, e.g. foo/bar,foo/zaz")
    public List<String> debugPrefixes = new ArrayList<>();

    @Option(name = { "-li", "--use-internal-patterns" }, description = "use hardcoded include/excludes that generally work well for internal repos")
    public boolean useInternalPatterns;

    @Override
    protected void runIfChecksOkay() {
      try {
        ChannelFactory channelFactory = () -> NettyChannelBuilder //
          .forAddress(host, port)
          .negotiationType(NegotiationType.PLAINTEXT)
          .keepAliveTime(keepAliveInSeconds, TimeUnit.SECONDS)
          .keepAliveTimeout(keepAliveTimeoutInSeconds, TimeUnit.SECONDS)
          .maxInboundMessageSize(maxMessageSize)
          .build();

        PathRules includes = new PathRules();
        PathRules excludes = new PathRules();
        setupIncludesAndExcludes(includes, excludes, extraIncludes, extraExcludes, useInternalPatterns);

        TaskFactory taskFactory = new ThreadBasedTaskFactory();
        FileWatcherFactory watcherFactory = FileWatcherFactory.newFactory(taskFactory);

        MirrorClient client = new MirrorClient(//
          new MirrorPaths(Paths.get(localRoot), Paths.get(remoteRoot), includes, excludes, debugAll, debugPrefixes),
          taskFactory,
          new ConnectionDetector.Impl(channelFactory),
          watcherFactory,
          new NativeFileAccess(Paths.get(localRoot).toAbsolutePath()),
          channelFactory);
        client.startSession();
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
    // vim safe write files
    excludes.addRule("*~");
    // Ignore .git/.svn directories
    excludes.addRule(".git/");
    excludes.addRule(".svn/");
    excludes.addRule(".watchman-cookie*");
    // Eclipse noise
    excludes.addRule(".tmpBin");
    // It's unlikely we want to copy around huge binary files by default
    excludes.addRule("*.gz");
    excludes.addRule("*.tar");
    excludes.addRule("*.zip");
    // Generally assume mirror users want to sync IDE files
    includes.addRules(".classpath", ".project");
    // Kind of verbose, but only include 1st/2nd level IDEA files because *.iml has other uses
    includes.addRules("/*.iml", "/*/*.iml", "/*.ipr", "/*/*.ipr", "/*.iws", "/*/*.iws");
  }

  private static void addInternalDefaults(PathRules includes, PathRules excludes) {
    // Maybe most of these could be dropped if svn:ignore was supported?
    excludes.addRules("tmp", "temp", "target", "build");
    // these are resources in the build/ directory that are still useful to have
    // on the laptop, e.g. for the IDE
    includes.addRules(
      // include generated source code
      "src_managed",
      "**/build/generated",
      "**/src/main/codegen",
      // pegasus/data-templates output
      "**/src/mainGeneratedRest",
      "**/src/mainGeneratedDataTemplate",
      "**/src/mainGeneratedInternalUrns/",
      "**/src/testGeneratedRest",
      "**/src/testGeneratedDataTemplate",
      // play generated urls
      "**/target/scala-2.10/routes/main",
      // sync the MP-level config directory, but not the svn directory within it
      "*/config",
      "!*/config/**/.svn",
      // include typings for TS
      "@types",
      // include the binaries the laptop-side IDE will want
      "*-SNAPSHOT.jar");
  }

  public static String getVersion() {
    String version = null;
    URL url = Mirror.class.getResource("/META-INF/MANIFEST.MF");
    try {
      try (InputStream in = url.openStream()) {
        Manifest m = new Manifest(in);
        version = m.getMainAttributes().getValue("Mirror-Version");
      }
    } catch (Exception e) {
      log.error("Error loading manifest", e);
    }
    return StringUtils.defaultIfEmpty(version, "unspecified");
  }

}

package mirror;

import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import io.grpc.internal.ManagedChannelImpl;

/**
 * Initializes a minimal/readable logback config.
 *
 * We use logback because it supports MDC.
 */
public class LoggingConfig {

  private static final String pattern = "%date{YYYY-MM-dd HH:mm:ss} %-5level %msg%n";
  private static volatile boolean started = false;

  public synchronized static void init() {
    if (started) {
      return;
    }
    started = true;

    // setup java.util.logging (which grpc-java uses) to go to slf4j
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();

    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    LevelChangePropagator p = new LevelChangePropagator();
    p.setContext(context);
    p.start();
    context.addListener(p);

    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(pattern);
    encoder.start();

    ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
    console.setContext(context);
    console.setEncoder(encoder);
    console.start();

    Logger root = getLogger(Logger.ROOT_LOGGER_NAME);
    root.detachAndStopAllAppenders();
    root.addAppender(console);
    root.setLevel(Level.INFO);

    getLogger("io.grpc").setLevel(Level.INFO);
    // silence a noisy DNS warning when we cannot resolve the other host
    getLogger(ManagedChannelImpl.class.getName()).setLevel(Level.ERROR);
    // silence "ConnectivityStateManager is already disabled" warning
    getLogger("io.grpc.internal.ChannelExecutor").setLevel(Level.ERROR);
    getLogger("mirror").setLevel(Level.INFO);
  }

  public synchronized static void initWithTracing() {
    init();
    getRootLogger().setLevel(Level.TRACE);
  }

  public synchronized static void enableLogFile() {
    init();

    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(pattern);
    encoder.start();

    FileAppender<ILoggingEvent> file = new FileAppender<>();
    file.setContext(context);
    file.setAppend(true);
    file.setFile("mirror.log");
    file.setEncoder(encoder);
    file.start();
    getRootLogger().addAppender(file);
  }

  private static Logger getRootLogger() {
    return getLogger(Logger.ROOT_LOGGER_NAME);
  }

  private static Logger getLogger(String name) {
    return (Logger) LoggerFactory.getLogger(name);
  }

}

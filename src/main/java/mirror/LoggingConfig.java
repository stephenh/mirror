package mirror;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class LoggingConfig {

  private static final String saneOneLineFormat = "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$-6s %5$s%6$s%n";

  public synchronized static void init() {
    System.setProperty("java.util.logging.SimpleFormatter.format", saneOneLineFormat);
    for (Handler h : Logger.getLogger("").getHandlers()) {
      h.setLevel(Level.INFO);
    }
    // Logger.getLogger("io.grpc").setLevel(Level.FINEST);
    // Logger.getLogger("mirror").setLevel(Level.FINEST);
  }

  public synchronized static void initWithTracing() {
    System.setProperty("java.util.logging.SimpleFormatter.format", saneOneLineFormat);
    for (Handler h : Logger.getLogger("").getHandlers()) {
      h.setLevel(Level.FINEST);
    }
    Logger.getLogger("mirror").setLevel(Level.FINEST);
  }

  public synchronized static void enableLogFile() {
    try {
      FileHandler f = new FileHandler("mirror.log");
      f.setLevel(Level.FINEST);
      f.setFormatter(new SimpleFormatter());
      Logger.getLogger("").addHandler(f);
      Logger.getLogger("mirror").setLevel(Level.FINEST);
    } catch (SecurityException | IOException e) {
      throw new RuntimeException(e);
    }
  }

}

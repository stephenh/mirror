package mirror;

class LoggingConfig {

  static {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$-6s %2$s %5$s%6$s%n");
  }

  public static void init() {
  }
}

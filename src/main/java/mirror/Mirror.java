package mirror;

public class Mirror {

  static {
    LoggingConfig.init();
  }

  public static void main(String[] args) throws Exception {
    MirrorServer.main(new String[] { "/home/stephen/dir1", "10000" });
    MirrorClient.main(new String[] { "/home/stephen/dir2", "localhost", "10000" });
  }

}

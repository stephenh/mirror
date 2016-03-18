package mirror;

import java.util.List;

import javax.inject.Inject;

import io.airlift.airline.Command;
import io.airlift.airline.HelpOption;
import io.airlift.airline.Option;
import io.airlift.airline.SingleCommand;

// Doesn't work yet, run MirrorServer and MirrorClient directly.
public class Mirror {

  static {
    LoggingConfig.init();
  }

  public static void main(String[] args) throws Exception {
    // MirrorServer.main(new String[] { "/home/stephen/dir1", "10000" });
    // MirrorClient.main(new String[] { "/home/stephen/dir2", "localhost", "10000" });
    MirrorCommand c = SingleCommand.singleCommand(MirrorCommand.class).parse(args);
    if (c.help.showHelpIfRequested()) {
      return;
    }
    c.run();
  }

  @Command(name = "mirror", description = "two-way real-time sync")
  public static class MirrorCommand {
    @Inject
    public HelpOption help;

    @Option(name = { "-f" }, description = "filters")
    public List<String> filters;

    public void run() {
      System.out.println("filters=" + filters);
    }
  }

}

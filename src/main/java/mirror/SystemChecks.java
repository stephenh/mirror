package mirror;

import static jnr.constants.platform.darwin.RLIMIT.RLIMIT_NOFILE;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import jnr.posix.RLimit;
import joist.util.Execute;
import joist.util.Execute.BufferedResult;

/**
 * Does some very basic checks against system/OS limits.
 * 
 * Currently this is just hardcoded with "you'll probably need at
 * least x,000" values, instead of intelligently gauging how big
 * the user's sync directory is going to be, and then not warning
 * them if it's really small.
 *
 * Doing that would be preferable, but this is simpler, and the
 * user can opt-out with a CLI flag.
 *
 * This checks are based on the Facebook Watchman recommendations:
 * 
 * https://facebook.github.io/watchman/docs/install.html#system-specific-preparation
 */
public class SystemChecks {

  private static final POSIX posix = POSIXFactory.getNativePOSIX();
  private static final Logger log = LoggerFactory.getLogger(SystemChecks.class);

  /**
   * @return true if the system passes our best-guess capacity checkso
   */
  public static boolean checkLimits() {
    return checkFileDescriptorLimit() && checkMaxUserWatches();
  }

  /**
   * I'm not actually sure if mirror needs this check, because Watchman insinuates
   * it's likely to be low on Mac OS, but I don't use Mac OS, so I can't really say
   * how often/if this would happen.
   * 
   * But it's easy enough to check for anyway, and really most developer machines
   * should have an increased ulimit anyway.
   */
  private static boolean checkFileDescriptorLimit() {
    RLimit limit = posix.getrlimit(RLIMIT_NOFILE.intValue());
    if (limit.rlimCur() < limit.rlimMax()) {
      log.info("Increasing file limit to {}", limit.rlimMax());
      posix.setrlimit(RLIMIT_NOFILE.intValue(), limit.rlimMax(), limit.rlimMax());
      limit = posix.getrlimit(RLIMIT_NOFILE.intValue());
    }
    if (limit.rlimCur() < 10240) {
      log.error("Your file limit is {} and should probably be increased", limit);
      log.info("  See https://facebook.github.io/watchman/docs/install.html#system-specific-preparation");
      log.info("  E.g. run: sudo sysctl -w kern.maxfiles=10485760 && sudo sysctl -w kern.maxfilesperproc=1048576");
      log.info("  Or use --skip-limit-checks to ignore this");
      return false;
    }
    return true;
  }

  private static boolean checkMaxUserWatches() {
    BufferedResult r = new Execute("cat").addEnvPaths().arg("/proc/sys/fs/inotify/max_user_watches").toBuffer();
    // only assume this will return 0 on linux, which is all we need to check
    if (r.exitValue == 0) {
      int maxUserWatches = Integer.parseInt(StringUtils.chomp(r.out));
      if (maxUserWatches < 10_000) {
        log.error("Your max_user_watches is {} and should probably be increased (each directory == 1 watch)", maxUserWatches);
        log.info("  See https://github.com/guard/listen/wiki/Increasing-the-amount-of-inotify-watchers");
        log.info("  E.g. run: echo fs.inotify.max_user_watches=524288 | sudo tee -a /etc/sysctl.conf && sudo sysctl -p");
        log.info("  Or use --skip-limit-checks to ignore this");
        return false;
      }
    }
    return true;
  }

}

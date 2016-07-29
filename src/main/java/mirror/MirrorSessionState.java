package mirror;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Holds the state (currently just stopped/not-stopped) and a list of callbacks.
 */
public class MirrorSessionState {

  private static final Logger log = LoggerFactory.getLogger(MirrorSessionState.class);
  private final List<Runnable> callbacks = new ArrayList<>();
  private boolean stopped = false;

  /**
   * Adds {@code r} to be called when the session is terminated.
   */
  public synchronized void addStoppedCallback(Runnable r) {
    callbacks.add(r);
  }

  /**
   * Called when the session is over, or when an unhandled exception happens (e.g. network error) and we need to restart the session.
   *
   * We don't technically restart the session here, but instead assume one of
   * the callbacks will kick off that process.
   */
  public synchronized void stop() {
    if (stopped) {
      log.warn("Session is already stopped");
    } else {
      stopped = true;
      invokeOnSeparateThread(new ArrayList<>(callbacks));
    }
  }

  public Runnable stopOnFailure() {
    return () -> {
      log.error("Failure indicated, so stopping the session");
      stop();
    };
  }

  private static void invokeOnSeparateThread(final List<Runnable> callbacks) {
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("MirrorSessionStateCloser-%s").build().newThread(() -> {
      callbacks.forEach(r -> {
        try {
          r.run();
        } catch (Exception e) {
          log.error("Error calling callback", e);
        }
      });
      log.info("All callbacks complete");
    }).start();
  }
}

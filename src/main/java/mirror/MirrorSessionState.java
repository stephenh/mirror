package mirror;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
   * Called when the session is over, or most likely when an unhandled exception happens (e.g. network error) and we need to restart the session.
   */
  public synchronized void stop() {
    if (!stopped) {
      stopped = true;
      callbacks.forEach(r -> {
        try {
          r.run();
        } catch (Exception e) {
          log.error("Error calling callback", e);
        }
      });
    }
  }

  public Runnable stopOnFailure() {
    return () -> stop();
  }
}

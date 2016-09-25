package mirror.tasks;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

/**
 * A task to be executed on a dedicated thread.
 */
public interface TaskLogic {

  /** Run one interaction, and return how long the task wants to sleep. */
  Duration runOneLoop() throws InterruptedException;

  /** Called on the task thread, before we start calling {@link #runOneLoop()} in a loop. */
  default void onStart() throws InterruptedException {
  }

  default void onFailure() throws InterruptedException {
  }

  /** Called on the task thread, after we've interrupted/stopped calling {@link #runOneLoop()}. */
  default void onStop() throws InterruptedException {
  }

  /**
   * Called off the task thread, when we're trying to interrupt the task.
   *
   * Most threads shouldn't need this because they'll shut down
   * when we call thread.interrupt().
   */
  default void onInterrupt() {
  }

  default String getName() {
    String name = getClass().getSimpleName();
    // lambdas don't have simple names
    if (name.equals("")) {
      name = StringUtils.substringAfterLast(getClass().getName(), ".");
    }
    return name;
  }
}

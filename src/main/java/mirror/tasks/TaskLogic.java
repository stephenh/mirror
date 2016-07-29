package mirror.tasks;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;

/**
 * A task to be executed on a dedicated thread.
 */
public interface TaskLogic {

  /** Run one interaction, and return how long the task wants to sleep. */
  Duration runOneLoop() throws InterruptedException;

  default void onStart() throws InterruptedException {
  }

  default void onFailure() throws InterruptedException {
  }

  default void onStop() throws InterruptedException {
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

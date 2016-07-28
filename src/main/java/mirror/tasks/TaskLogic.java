package mirror.tasks;

import java.time.Duration;

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
    return getClass().getSimpleName();
  }
}

package mirror.tasks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs each task on a dedicated thread, kind of like actors, only more expensive.
 */
public class ThreadBasedTaskFactory implements TaskFactory {

  private final Map<TaskLogic, ThreadBasedTask> tasks = new ConcurrentHashMap<>();

  @Override
  public TaskHandle runTask(TaskLogic logic, Runnable onFailure) {
    ThreadBasedTask task = new ThreadBasedTask(logic, onFailure);
    tasks.put(logic, task);
    task.start();
    return () -> stopTask(logic);
  }

  // Note that this method isn't synchronized, as while shutting down one task,
  // while we block on task.stop completing, the task might ask us to shut down
  // one of it's child tasks, but from it's own thread, which would block.
  @Override
  public void stopTask(TaskLogic logic) {
    ThreadBasedTask task = tasks.get(logic);
    if (task == null) {
      throw new IllegalArgumentException("No task found for " + logic);
    }
    tasks.remove(task);
    task.stop();
  }

}

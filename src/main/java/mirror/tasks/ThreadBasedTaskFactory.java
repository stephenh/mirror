package mirror.tasks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs each task on a dedicated thread, kind of like actors, only more expensive.
 */
public class ThreadBasedTaskFactory implements TaskFactory {

  private final ConcurrentHashMap<TaskLogic, ThreadBasedTask> tasks = new ConcurrentHashMap<>();

  @Override
  public TaskHandle runTask(TaskLogic logic, Runnable onFailure) {
    ThreadBasedTask task = new ThreadBasedTask(logic, onFailure, () -> {
      // we don't need to call stopTask(logic) or task.stop because the task is already stopping,
      // we just to ensure we remove this entry from our map to avoid memory leaks
      tasks.remove(logic);
    });
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
    if (task != null) {
      tasks.remove(logic);
      task.stop();
    }
  }

}

package mirror.tasks;

/**
 * An abstraction for running tasks on dedicated threads, e.g. periodic daemons.
 *
 * Instead of creating, running, and shutting threads directly, code can just ask for tasks
 * to be ran/stopped.
 *
 * This is similar to executors or actors, but meant to be simpler, e.g. each task will get
 * it's own thread, so it can block without filling up the executor pool, and basically
 * pretend to be an actor, by communicating with the other tasks via thread-safe queues. 
 */
public interface TaskFactory {

  default TaskHandle runTask(TaskLogic logic) {
    return runTask(logic, null);
  }

  TaskHandle runTask(TaskLogic logic, Runnable onFailure);

  void stopTask(TaskLogic logic);

  default TaskPool newTaskPool() {
    return new TaskPool(this);
  }

}

package mirror.tasks;

public interface TaskFactory {

  default TaskHandle runTask(TaskLogic logic) {
    return runTask(logic, null);
  }

  TaskHandle runTask(TaskLogic logic, Runnable onFailure);

  void stopTask(TaskLogic logic);

}

package mirror.tasks;

public interface TaskFactory {

  TaskHandle runTask(TaskLogic logic);

  void stopTask(TaskLogic logic);

}

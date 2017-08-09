package mirror.tasks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import mirror.Utils;

public class StubTaskFactory implements TaskFactory {

  private final Map<TaskLogic, StubTask> tasks = new ConcurrentHashMap<>();

  @Override
  public TaskHandle runTask(TaskLogic logic, Runnable onFailure) {
    StubTask task = new StubTask(logic, onFailure);
    tasks.put(logic, task);
    return () -> stopTask(logic);
  }

  @Override
  public void stopTask(TaskLogic logic) {
    StubTask task = tasks.get(logic);
    if (task != null) {
      tasks.remove(logic);
      Utils.resetIfInterrupted(() -> task.stop());
    }
  }

  public void tick() {
    tasks.values().forEach(t -> Utils.resetIfInterrupted(() -> t.tick()));
  }

  public Duration getLastDuration(TaskLogic logic) {
    return tasks.get(logic).lastDuration;
  }
}

package mirror.tasks;

import java.time.Duration;

class StubTask {

  final TaskLogic logic;
  final Runnable onFailure;
  Duration lastDuration;

  public StubTask(TaskLogic logic, Runnable onFailure) {
    this.logic = logic;
    this.onFailure = onFailure;
  }

  void tick() throws InterruptedException {
    lastDuration = logic.runOneLoop();
  }

  void stop() throws InterruptedException {
    logic.onStop();
  }
}

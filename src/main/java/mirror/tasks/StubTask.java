package mirror.tasks;

import java.time.Duration;

class StubTask {

  final TaskLogic logic;
  Duration lastDuration;

  public StubTask(TaskLogic logic) {
    this.logic = logic;
  }

  void tick() throws InterruptedException {
    lastDuration = logic.runOneLoop();
  }

  void stop() throws InterruptedException {
    logic.onStop();
  }
}

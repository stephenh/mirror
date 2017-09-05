package mirror.tasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pool of tasks whose lifetime is related: if one task fails, then all
 * of the tasks will be shutdown.
 */
public class TaskPool {

  private static final Logger log = LoggerFactory.getLogger(TaskPool.class);
  private final TaskFactory factory;
  private final List<TaskLogic> tasks = new CopyOnWriteArrayList<>();
  private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
  private final AtomicBoolean shutdown = new AtomicBoolean(false);

  public TaskPool(TaskFactory factory) {
    this.factory = factory;
  }

  public TaskHandle runTask(TaskLogic logic) {
    if (shutdown.get()) {
      throw new IllegalStateException("Pool is shutdown");
    }
    TaskHandle h = factory.runTask(logic, this::stopAllTasks);
    tasks.add(logic);
    return h;
  }

  public void stopTask(TaskLogic logic) {
    factory.stopTask(logic);
    tasks.remove(logic);
  }

  public void stopAllTasks() {
    // Several places call MirrorSession.stop when shutting down, so don't
    // error out if this is already shut down
    if (shutdown.compareAndSet(false, true)) {
      factory.runTask(new StopTasksInPool());
    }
  }

  public void addShutdownCallback(Runnable callback) {
    callbacks.add(callback);
  }

  private class StopTasksInPool implements TaskLogic {
    @Override
    public Duration runOneLoop() throws InterruptedException {
      new ArrayList<>(tasks).forEach(t -> {
        try {
          stopTask(t);
        } catch (Exception e) {
          log.error("Error calling callback", e);
        }
      });
      callbacks.forEach(r -> {
        try {
          r.run();
        } catch (Exception e) {
          log.error("Error calling callback", e);
        }
      });
      log.debug("All callbacks complete");
      return Duration.ofMillis(-1);
    }
  }

}

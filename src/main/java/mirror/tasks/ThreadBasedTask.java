package mirror.tasks;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import mirror.Utils;

/**
 * Provides some basic "start up a thread and poll a queue" abstractions.
 *
 * Kind of actor-like. Ish.
 */
class ThreadBasedTask {

  private static int nextThread = 0;
  protected final Logger log = LoggerFactory.getLogger(getClass());
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private final CountDownLatch isStarted = new CountDownLatch(1);
  private final CountDownLatch isShutdown = new CountDownLatch(1);
  private final Thread thread;
  private final TaskLogic task;

  private static synchronized int nextThreadId() {
    return nextThread++;
  }

  protected ThreadBasedTask(TaskLogic task) {
    this.task = task;
    thread = new ThreadFactoryBuilder()
      .setDaemon(true)
      .setNameFormat(task.getClass().getSimpleName() + "-" + nextThreadId() + "-%s")
      .build()
      .newThread(() -> run());
    thread.start();
  }

  void stop() {
    Utils.resetIfInterrupted(() -> isStarted.await());
    shutdown.set(true);
    thread.interrupt();
    Utils.resetIfInterrupted(() -> isShutdown.await());
  }

  private void run() {
    try {
      isStarted.countDown();
      try {
        task.onStart();
        while (!shouldStop()) {
          Duration wait = task.runOneLoop();
          if (wait != null) {
            Thread.sleep(wait.toMillis());
          }
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        // shutting down
      } catch (Exception e) {
        log.error("Error returned from runOneLoop", e);
        task.onFailure();
      }
      task.onStop();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      // shutting down
    } finally {
      isShutdown.countDown();
    }
  }

  private boolean shouldStop() {
    return shutdown.get() || Thread.currentThread().isInterrupted();
  }

}

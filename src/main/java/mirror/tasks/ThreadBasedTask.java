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
  private final Runnable onFailure;
  private final Runnable onStop;

  private static synchronized int nextThreadId() {
    return nextThread++;
  }

  protected ThreadBasedTask(TaskLogic task, Runnable onFailure, Runnable onStop) {
    this.task = task;
    this.onFailure = onFailure;
    this.onStop = onStop;
    thread = new ThreadFactoryBuilder().setDaemon(true).setNameFormat(nextThreadId() + "-" + task.getName() + "-%s").build().newThread(() -> run());
  }

  void start() {
    thread.start();
    Utils.resetIfInterrupted(() -> isStarted.await());
  }

  void stop() {
    if (shutdown.compareAndSet(false, true)) {
      Utils.resetIfInterrupted(() -> isStarted.await());
      thread.interrupt();
      task.onInterrupt();
      Utils.resetIfInterrupted(() -> isShutdown.await());
    }
  }

  private void run() {
    try {
      isStarted.countDown();
      try {
        task.onStart();
        while (!shouldStop()) {
          Duration wait = task.runOneLoop();
          if (wait != null) {
            if (wait.isNegative()) {
              break;
            }
            Thread.sleep(wait.toMillis());
          }
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        // shutting down
      } catch (Exception e) {
        log.error("Error returned from runOneLoop", e);
        callTaskFailureCallback();
        callFactoryFailureCallback();
      }
      task.onStop();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      // shutting down
    } finally {
      callFactoryStopCallback();
      isShutdown.countDown();
    }
  }

  private void callTaskFailureCallback() {
    try {
      task.onFailure();
    } catch (Exception e2) {
      log.error("task.onFailure() call failed", e2);
    }
  }

  private void callFactoryFailureCallback() {
    if (onFailure != null) {
      try {
        onFailure.run();
      } catch (Exception e2) {
        log.error("onFailure call failed", e2);
      }
    }
  }

  private void callFactoryStopCallback() {
    try {
      onStop.run();
    } catch (Exception e) {
      log.error("onStop call failed", e);
    }
  }

  private boolean shouldStop() {
    return shutdown.get() || Thread.currentThread().isInterrupted();
  }

}

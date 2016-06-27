package mirror;

import static mirror.Utils.handleInterrupt;

import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Provides some basic "start up a thread and poll a queue" abstractions.
 *
 * Kind of actor-like. Ish.
 */
abstract class AbstractThreaded {

  protected final Logger log = LoggerFactory.getLogger(getClass());
  protected volatile boolean started = false;
  protected volatile boolean shutdown = false;
  protected volatile Runnable onFailure;
  private final CountDownLatch isShutdown = new CountDownLatch(1);
  private final Thread thread;

  protected AbstractThreaded() {
    thread = new ThreadFactoryBuilder() //
      .setDaemon(true)
      .setNameFormat(getClass().getSimpleName() + "-%s")
      .build()
      .newThread(() -> run());
  }

  /**
   * Starts a thread running the {@code pollLoop} logic that is implemented by the subclass.
   */
  public final synchronized void start(Runnable onFailure) {
    if (started) {
      throw new IllegalStateException("Already started");
    }
    this.onFailure = onFailure;
    started = true;
    thread.start();
  }

  /**
   * Initiates and waits for shutdown of our thread; the subclass's loop
   * should watch for !shutdown/doStop and stop their loop appropriately.
   */
  public final synchronized void stop() {
    shutdown = true;
    if (!started) {
      return;
    }
    thread.interrupt();
    handleInterrupt(() -> isShutdown.await());
  }

  private void run() {
    try {
      try {
        pollLoop();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        // shutting down
      } catch (Exception e) {
        log.error("Error escaped pollLoop", e);
        onFailure.run();
      }
      doStop();
    } finally {
      isShutdown.countDown();
    }
  }

  protected abstract void pollLoop() throws InterruptedException;

  protected void doStop() {
  }

}

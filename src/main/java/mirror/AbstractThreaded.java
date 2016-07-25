package mirror;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

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
  protected volatile Runnable onFailure;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
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
    if (started.get()) {
      throw new IllegalStateException("Already started");
    }
    this.onFailure = onFailure;
    started.set(true);
    thread.start();
  }

  /**
   * Initiates and waits for shutdown of our thread; the subclass's loop
   * should watch for {@link #shouldStop()} and stop their loop appropriately.
   */
  public final synchronized void stop() {
    shutdown.set(true);
    if (!started.get()) {
      return;
    }
    thread.interrupt();
    Utils.resetIfInterrupted(() -> isShutdown.await());
  }

  protected boolean shouldStop() {
    return shutdown.get() || Thread.currentThread().isInterrupted();
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

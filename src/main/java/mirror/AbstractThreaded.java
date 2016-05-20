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
  protected final CountDownLatch isShutdown = new CountDownLatch(1);
  private final MirrorSessionState sessionState;

  protected AbstractThreaded(MirrorSessionState sessionState) {
    this.sessionState = sessionState;
  }

  /**
   * Starts a thread running the {@code pollLoop} logic that is implemented by the subclass.
   */
  public final void start() {
    if (started) {
      throw new IllegalStateException("Already started");
    }
    started = true;
    Runnable runnable = () -> {
      try {
        handleInterrupt(() -> pollLoop());
      } catch (Exception e) {
        log.error("Error escaped pollLoop", e);
        sessionState.stop();
      } finally {
        isShutdown.countDown();
      }
    };
    new ThreadFactoryBuilder() //
      .setDaemon(true)
      .setNameFormat(getClass().getSimpleName() + "-%s")
      .build()
      .newThread(runnable)
      .start();
  }

  /**
   * Initiates and waits for shutdown of our thread; the subclass's loop
   * should watch for !shutdown/doStop and stop their loop appropriately.
   */
  public final void stop() {
    shutdown = true;
    if (!started) {
      return;
    }
    try {
      doStop();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    handleInterrupt(() -> isShutdown.await());
  }

  protected abstract void pollLoop() throws InterruptedException;

  protected void doStop() throws Exception {
  }

}

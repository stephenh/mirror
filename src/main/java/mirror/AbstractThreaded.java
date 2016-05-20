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
  protected volatile boolean shutdown = false;
  protected final CountDownLatch isShutdown = new CountDownLatch(1);

  public final void start() {
    Runnable runnable = () -> {
      try {
        handleInterrupt(() -> pollLoop());
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

  public final void stop() {
    shutdown = true;
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

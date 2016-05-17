package mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class QueueWatcher {

  private static final Logger log = LoggerFactory.getLogger(QueueWatcher.class);
  private final Queues queues;
  private int lastUpdates;
  private int lastLocal;
  private int lastRemote;

  public QueueWatcher(Queues queues) {
    this.queues = queues;
  }

  public void startWatching() {
    Runnable runnable = () -> {
      try {
        pollLoop();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // TODO need to signal that our connection needs reset
        throw new RuntimeException(e);
      }
    };
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("QueueWatcher-%s").build().newThread(runnable).start();
  }

  public void stop() throws InterruptedException {
  }

  private void pollLoop() throws InterruptedException {
    while (true) {
      int updates = queues.incomingQueue.size();
      int local = queues.saveToLocal.size();
      int remote = queues.saveToRemote.size();
      if (updates != lastUpdates || local != lastLocal || remote != lastRemote) {
        log.info("Queues: updates=" + updates + ", local=" + local + ", remote=" + remote);
        lastUpdates = updates;
        lastLocal = local;
        lastRemote = remote;
      }
      Thread.sleep(1000);
    }
  }

}

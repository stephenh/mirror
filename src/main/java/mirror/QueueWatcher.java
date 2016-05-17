package mirror;

import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import mirror.UpdateTreeDiff.DiffResults;

public class QueueWatcher {

  private static final Logger log = LoggerFactory.getLogger(QueueWatcher.class);
  private final Queue<Update> incomingUpdates;
  private final Queue<DiffResults> outgoingDiffs;
  private int lastUpdates;
  private int lastDiffs;

  public QueueWatcher(Queue<Update> incomingUpdates, Queue<DiffResults> outgoingDiffs) {
    this.incomingUpdates = incomingUpdates;
    this.outgoingDiffs = outgoingDiffs;
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
      int updates = incomingUpdates.size();
      int diffs = outgoingDiffs.size();
      if (updates != lastUpdates || diffs != lastDiffs) {
        log.info("Queues: updates=" + updates + ", diffs=" + diffs);
        lastUpdates = updates;
        lastDiffs = diffs;
      }
      Thread.sleep(1000);
    }
  }

}

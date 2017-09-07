package mirror;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mirror.tasks.TaskLogic;

public class QueueWatcher implements TaskLogic {

  private static final Logger log = LoggerFactory.getLogger(QueueWatcher.class);
  private final Queues queues;
  private int lastIncomingQueue;
  private int lastSaveToLocal;
  private int lastSaveToRemote;

  public QueueWatcher(Queues queues) {
    this.queues = queues;
  }

  @Override
  public Duration runOneLoop() {
    int incomingQueue = queues.incomingQueue.size();
    int saveToLocal = queues.saveToLocal.size();
    int saveToRemote = queues.saveToRemote.size();
    if (isStillHigh(lastIncomingQueue, incomingQueue) || isStillHigh(lastSaveToLocal, saveToLocal) || isStillHigh(lastSaveToRemote, saveToRemote)) {
      log.info("Queues: incomingQueue=" + incomingQueue + ", saveToLocal=" + saveToLocal + ", saveToRemote=" + saveToRemote);
      lastIncomingQueue = incomingQueue;
      lastSaveToLocal = saveToLocal;
      lastSaveToRemote = saveToRemote;
    }
    return Duration.ofMillis(250);
  }

  /** @return true if the queue has been non-zero for our last two checks. */
  private static boolean isStillHigh(int lastSize, int currentSize) {
    return lastSize != 0 && currentSize != 0;
  }
}

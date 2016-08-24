package mirror;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mirror.tasks.TaskLogic;

public class QueueWatcher implements TaskLogic {

  private static final Logger log = LoggerFactory.getLogger(QueueWatcher.class);
  private final Queues queues;
  private int lastUpdates;
  private int lastLocal;
  private int lastRemote;

  public QueueWatcher(Queues queues) {
    this.queues = queues;
  }

  @Override
  public Duration runOneLoop() {
    int updates = queues.incomingQueue.size();
    int local = queues.saveToLocal.size();
    int remote = queues.saveToRemote.size();
    if (updates != lastUpdates || local != lastLocal || remote != lastRemote) {
      log.debug("Queues: updates=" + updates + ", local=" + local + ", remote=" + remote);
      lastUpdates = updates;
      lastLocal = local;
      lastRemote = remote;
    }
    return Duration.ofMillis(250);
  }

}

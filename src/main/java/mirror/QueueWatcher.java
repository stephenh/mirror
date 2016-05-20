package mirror;

public class QueueWatcher extends AbstractThreaded {

  private final Queues queues;
  private int lastUpdates;
  private int lastLocal;
  private int lastRemote;

  public QueueWatcher(MirrorSessionState state, Queues queues) {
    super(state);
    this.queues = queues;
  }

  @Override
  protected void pollLoop() throws InterruptedException {
    while (!shutdown) {
      int updates = queues.incomingQueue.size();
      int local = queues.saveToLocal.size();
      int remote = queues.saveToRemote.size();
      if (updates != lastUpdates || local != lastLocal || remote != lastRemote) {
        log.info("Queues: updates=" + updates + ", local=" + local + ", remote=" + remote);
        lastUpdates = updates;
        lastLocal = local;
        lastRemote = remote;
      }
      Thread.sleep(250);
    }
  }

}

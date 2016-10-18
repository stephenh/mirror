package mirror;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Queues {

  // this is probably too large, but hopefully it will avoid inotify overflow exceptions
  public final BlockingQueue<Update> incomingQueue = new LinkedBlockingQueue<>(1_000_000);
  // These need to be large enough so that when the SyncLogic thread is outputting the
  // results of a diff, and there are ~10k/100k new files to send, that it can completely
  // offload those Updates to the saveToRemote queue, so that it can unblock, and start
  // accepting new Updates from the incomingQueue.
  public final BlockingQueue<Update> saveToLocal = new LinkedBlockingQueue<>(1_000_000);
  public final BlockingQueue<Update> saveToRemote = new LinkedBlockingQueue<>(1_000_000);

}

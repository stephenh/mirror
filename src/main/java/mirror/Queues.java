package mirror;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Queues {

  // this is probably too large, but hopefully it will avoid inotify overflow exceptions
  public final BlockingQueue<Update> incomingQueue = new ArrayBlockingQueue<>(1_000_000);
  public final BlockingQueue<Update> saveToLocal = new ArrayBlockingQueue<>(1_000);
  public final BlockingQueue<Update> saveToRemote = new ArrayBlockingQueue<>(1_000);

}

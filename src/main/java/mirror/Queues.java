package mirror;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import mirror.UpdateTreeDiff.DiffResults;

public class Queues {

  public final BlockingQueue<Update> incomingQueue = new ArrayBlockingQueue<>(1_000_000);
  public final BlockingQueue<DiffResults> resultQueue = new ArrayBlockingQueue<>(1_000_000);

}

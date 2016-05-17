package mirror;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.grpc.stub.StreamObserver;
import mirror.UpdateTreeDiff.DiffResults;

public class ResultsSender {

  private static final Logger log = LoggerFactory.getLogger(ResultsSender.class);
  private static final DiffResults shutdownResults = new DiffResults();
  private final BlockingQueue<DiffResults> results;
  private final DiffApplier applier;
  private volatile boolean shutdown = false;
  private final CountDownLatch isShutdown = new CountDownLatch(1);

  public ResultsSender(String role, BlockingQueue<DiffResults> results, StreamObserver<Update> outgoingChanges, FileAccess fileAccess) {
    this.results = results;
    this.applier = new DiffApplier(role, outgoingChanges, fileAccess);
  }

  public void startSending() {
    Runnable runnable = () -> {
      try {
        pollLoop();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // TODO need to signal that our connection needs reset
        throw new RuntimeException(e);
      }
    };
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("ResultsSender-%s").build().newThread(runnable).start();
  }

  public void stop() throws InterruptedException {
    shutdown = true;
    results.clear();
    results.add(shutdownResults);
    isShutdown.await();
  }

  private void pollLoop() throws InterruptedException {
    while (!shutdown) {
      DiffResults r = results.take();
      try {
        applier.apply(r);
      } catch (Exception e) {
        log.error("Exception with results " + r, e);
      }
    }
    isShutdown.countDown();
  }

}

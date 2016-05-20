package mirror;

import static mirror.Utils.debugString;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;

import com.google.common.annotations.VisibleForTesting;

import io.grpc.stub.StreamObserver;

public class SaveToRemote extends AbstractThreaded {

  private static final Update shutdownUpdate = Update.newBuilder().build();
  private final FileAccess fileAccess;
  private final BlockingQueue<Update> results;
  private final StreamObserver<Update> outgoingChanges;

  public SaveToRemote(Queues queues, FileAccess fileAccess, StreamObserver<Update> outgoingChanges) {
    this.fileAccess = fileAccess;
    this.results = queues.saveToRemote;
    this.outgoingChanges = outgoingChanges;
  }

  @Override
  protected void pollLoop() throws InterruptedException {
    while (!shutdown) {
      Update u = results.take();
      if (u == shutdownUpdate) {
        return;
      }
      try {
        sendToRemote(u);
      } catch (Exception e) {
        log.error("Exception with results " + u, e);
      }
    }
  }

  @Override
  protected void doStop() throws InterruptedException {
    results.clear();
    results.add(shutdownUpdate);
  }

  @VisibleForTesting
  void drain() throws Exception {
    while (!results.isEmpty()) {
      sendToRemote(results.take());
    }
  }

  private void sendToRemote(Update update) {
    try {
      Update.Builder b = Update.newBuilder(update).setLocal(false);
      if (!update.getDirectory() && update.getSymlink().isEmpty() && !update.getDelete()) {
        b.setData(Utils.readDataFully(fileAccess, Paths.get(update.getPath())));
      }
      log.debug("Sending to remote " + update.getPath());
      outgoingChanges.onNext(b.build());
    } catch (InterruptedException e) {
      log.error("Interrupted " + debugString(update), e);
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.error("Could not read " + debugString(update), e);
    }
  }

}

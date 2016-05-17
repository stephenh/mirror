package mirror;

import static mirror.Utils.debugString;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.grpc.stub.StreamObserver;

public class SaveToRemote {

  private static final Logger log = LoggerFactory.getLogger(SaveToRemote.class);
  private final FileAccess fileAccess;
  private final BlockingQueue<Update> results;
  private final StreamObserver<Update> outgoingChanges;

  public SaveToRemote(Queues queues, FileAccess fileAccess, StreamObserver<Update> outgoingChanges) {
    this.fileAccess = fileAccess;
    this.results = queues.saveToRemote;
    this.outgoingChanges = outgoingChanges;
  }

  public void start() {
    Runnable runnable = () -> {
      try {
        pollLoop();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // TODO need to signal that our connection needs reset
        throw new RuntimeException(e);
      }
    };
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("SaveToLocal-%s").build().newThread(runnable).start();
  }

  public void stop() throws InterruptedException {
  }

  private void pollLoop() throws InterruptedException {
    while (true) {
      Update u = results.take();
      try {
        sendToRemote(u);
      } catch (Exception e) {
        log.error("Exception with results " + u, e);
      }
    }
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

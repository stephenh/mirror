package mirror;

import static mirror.Utils.debugString;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import io.grpc.stub.StreamObserver;
import mirror.tasks.TaskLogic;

public class SaveToRemote implements TaskLogic {

  private static final Logger log = LoggerFactory.getLogger(SaveToRemote.class);
  private final FileAccess fileAccess;
  private final BlockingQueue<Update> results;
  private final StreamObserver<Update> outgoingChanges;

  public SaveToRemote(Queues queues, FileAccess fileAccess, StreamObserver<Update> outgoingChanges) {
    this.fileAccess = fileAccess;
    this.results = queues.saveToRemote;
    this.outgoingChanges = outgoingChanges;
  }

  @Override
  public Duration runOneLoop() throws InterruptedException {
    Update u = results.take();
    try {
      sendToRemote(u);
    } catch (RuntimeException e) {
      log.error("Exception with results " + u, e);
    }
    return null;
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
        b.setData(fileAccess.read(Paths.get(update.getPath())));
      }
      String maybeDelete = update.getDelete() ? "(delete) " : "";
      log.debug("Sending to remote " + maybeDelete + update.getPath());
      outgoingChanges.onNext(b.build());
    } catch (IOException e) {
      // TODO Should we error here, so that the session is restarted?
      log.error("Could not read " + debugString(update), e);
    }
  }

}

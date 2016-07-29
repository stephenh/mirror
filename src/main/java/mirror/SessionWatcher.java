package mirror;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import mirror.tasks.TaskFactory;
import mirror.tasks.TaskLogic;

/**
 * Sends dummy updates back/forth over our streaming connections as make-shift pings.
 *
 * In theory grpc-java should notice when our two-way streaming connection goes down,
 * but it basically doesn't.
 */
public class SessionWatcher implements TaskLogic {

  public static final String heartbeatPath = "SessionWatcherHeartbeat";
  private static final Logger log = LoggerFactory.getLogger(SessionWatcher.class);
  private static final Duration timeout = Duration.ofMinutes(2);
  private final Clock clock;
  private final TaskFactory taskFactory;
  private final MirrorSessionState state;
  private final StreamObserver<Update> outgoingUpdates;
  private final TaskLogic heartbeat = new HeartbeatSender();
  private volatile Instant lastReceived = null;

  public SessionWatcher(Clock clock, TaskFactory taskFactory, MirrorSessionState state, StreamObserver<Update> outgoingUpdates) {
    this.clock = clock;
    this.taskFactory = taskFactory;
    this.state = state;
    this.outgoingUpdates = outgoingUpdates;
  }

  public void updateReceived(Update update) {
    lastReceived = Instant.now();
  }

  @Override
  public void onStart() {
    taskFactory.runTask(heartbeat);
  }

  @Override
  public void onStop() {
    taskFactory.stopTask(heartbeat);
  }

  @Override
  public Duration runOneLoop() {
    // ensure at least 2 minutes have gone by
    Instant now = clock.instant();
    if (lastReceived != null && Duration.between(lastReceived, now).minus(timeout).isNegative()) {
      log.error("Stopping session due to duration timeout");
      state.stop();
    }
    return timeout;
  }

  /**
   * We use a dedicated thread to send pings, because outgoingUpdates.onNext
   * will block if the sending queue is full.
   *
   * We'll send an update every (timeout / 2) so that the other side should
   * continually see updates from us.
   */
  public class HeartbeatSender implements TaskLogic {
    @Override
    public Duration runOneLoop() throws InterruptedException {
      outgoingUpdates.onNext(Update.newBuilder().setPath(heartbeatPath).build());
      return timeout.dividedBy(2);
    }
  }
}

package mirror;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mirror.tasks.TaskLogic;
import mirror.tasks.TaskPool;

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
  private final MirrorSession session;
  private final Clock clock;
  private final TaskPool taskPool;
  private final OutgoingConnection outgoing;
  private final TaskLogic heartbeat = new HeartbeatSender();
  private volatile Instant lastReceived = null;

  public SessionWatcher(MirrorSession session, Clock clock, TaskPool taskPool, OutgoingConnection outgoing) {
    this.session = session;
    this.clock = clock;
    this.taskPool = taskPool;
    this.outgoing = outgoing;
  }

  public void updateReceived(Update update) {
    lastReceived = Instant.now();
  }

  @Override
  public void onStart() {
    taskPool.runTask(heartbeat);
  }

  @Override
  public Duration runOneLoop() {
    // ensure at least 2 minutes have gone by
    Instant now = clock.instant();
    if (lastReceived != null && timeout.minus(Duration.between(lastReceived, now)).isNegative()) {
      log.error(//
        "Stopping session due to duration timeout (lastReceived={}, now={}, diff={})",
        lastReceived,
        now,
        Duration.between(lastReceived, now));
      session.stop();
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
      outgoing.send(Update.newBuilder().setPath(heartbeatPath).build());
      return timeout.dividedBy(2);
    }
  }
}

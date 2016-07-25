package mirror;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;

/**
 * Sends dummy updates back/forth over our streaming connections as make-shift pings.
 */
public class SessionWatcher extends AbstractThreaded {

  public static final String pingPath = "SessionWatcherPingPath";
  private static final Logger log = LoggerFactory.getLogger(SessionWatcher.class);
  private static final Duration timeout = Duration.ofMinutes(2);
  private final Clock clock;
  private final MirrorSessionState state;
  private final StreamObserver<Update> outgoingUpdates;
  private volatile Instant lastReceived = null;
  private volatile Instant lastSent = null;

  public SessionWatcher(Clock clock, MirrorSessionState state, StreamObserver<Update> outgoingUpdates) {
    this.clock = clock;
    this.state = state;
    this.outgoingUpdates = outgoingUpdates;
  }

  public void pingReceived(Update update) {
    lastReceived = Instant.now();
  }

  @Override
  protected void pollLoop() throws InterruptedException {
    while (!shouldStop()) {
      // ensure at least 2 minutes have gone by
      Instant now = clock.instant();
      boolean hasSentAtLeastTwoMinutesAgo = lastSent != null && lastSent.isBefore(now.minus(timeout));
      boolean hasNotReceivedRecently = hasSentAtLeastTwoMinutesAgo
        && (lastReceived == null || Duration.between(lastSent, lastReceived).minus(timeout).isNegative());
      if (hasSentAtLeastTwoMinutesAgo && hasNotReceivedRecently) {
        log.error("Stopping session due to duration timeout");
        state.stop();
      } else {
        outgoingUpdates.onNext(Update.newBuilder().setPath(pingPath).build());
        lastSent = now;
        Thread.sleep(30L); // Sleep for a bit
      }
    }
  }
}

package mirror;

import static mirror.Utils.handleInterrupt;
import static mirror.Utils.withTimeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorStub;

/**
 * Provides basic connection detection by explicit pings.
 *
 * In theory grpc has some Http2 ping facilities, but I wasn't sure
 * how to use it.
 */
public interface ConnectionDetector {

  void blockUntilConnected(MirrorStub stub);

  boolean isAvailable(MirrorStub stub);

  /** A stub/noop detector for unit tests. */
  public static class Noop implements ConnectionDetector {
    @Override
    public void blockUntilConnected(MirrorStub stub) {
    }

    @Override
    public boolean isAvailable(MirrorStub stub) {
      return true;
    }
  }

  /** A detector that uses our app-specific PingRequest/PingResponse. */
  public static class Impl implements ConnectionDetector {
    private static final Logger log = LoggerFactory.getLogger(Impl.class);

    @Override
    public boolean isAvailable(MirrorStub stub) {
      AtomicBoolean available = new AtomicBoolean(false);
      CountDownLatch done = new CountDownLatch(1);
      withTimeout(stub).ping(PingRequest.newBuilder().build(), new StreamObserver<PingResponse>() {
        @Override
        public void onNext(PingResponse value) {
          available.set(true);
        }

        @Override
        public void onError(Throwable t) {
          log.debug("Server not available: " + t.getMessage());
          done.countDown();
        }

        @Override
        public void onCompleted() {
          done.countDown();
        }
      });
      handleInterrupt(() -> done.await());
      return available.get();
    }

    @Override
    public void blockUntilConnected(MirrorStub stub) {
      while (!isAvailable(stub)) {
        handleInterrupt(() -> Thread.sleep(1_000));
      }
    }
  }

}

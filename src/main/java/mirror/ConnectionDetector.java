package mirror;

import static mirror.Utils.handleInterrupt;
import static mirror.Utils.withTimeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorStub;

public interface ConnectionDetector {

  void blockUntilConnected(MirrorStub stub);

  public static class Noop implements ConnectionDetector {
    @Override
    public void blockUntilConnected(MirrorStub stub) {
    }
  }

  public static class Impl implements ConnectionDetector {
    private static final Logger log = LoggerFactory.getLogger(Impl.class);

    @Override
    public void blockUntilConnected(MirrorStub stub) {
      handleInterrupt(() -> {
        AtomicBoolean available = new AtomicBoolean(false);
        while (!available.get()) {
          CountDownLatch done = new CountDownLatch(1);
          withTimeout(stub).ping(PingRequest.newBuilder().build(), new StreamObserver<PingResponse>() {
            @Override
            public void onNext(PingResponse value) {
              available.set(true);
            }

            @Override
            public void onError(Throwable t) {
              log.info("Server not available: " + t.getMessage());
              done.countDown();
            }

            @Override
            public void onCompleted() {
              done.countDown();
            }
          });
          done.await();
          if (!available.get()) {
            Thread.sleep(30_000);
          }
        }
      });
    }
  }

}

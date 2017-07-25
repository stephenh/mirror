package mirror;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.Channel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
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

  /** A stub/noop detector for unit tests. */
  public static class Noop implements ConnectionDetector {
    @Override
    public void blockUntilConnected(MirrorStub stub) {
    }
  }

  /** A detector that uses our app-specific PingRequest/PingResponse. */
  public static class Impl implements ConnectionDetector {
    private static final Duration durationBetweenDetections = Duration.ofSeconds(5);

    public static void main(String[] args) throws Exception {
      Channel c = NettyChannelBuilder.forAddress("shaberma-ld1", 49172).negotiationType(NegotiationType.PLAINTEXT).build();
      while (true) {
        new Impl().blockUntilConnected(MirrorGrpc.newStub(c).withCompression("gzip"));
        System.out.println("CONNECTED");
        Thread.sleep(5000);
      }
    }

    private boolean isAvailable(MirrorStub stub) {
      AtomicBoolean available = new AtomicBoolean(false);
      CountDownLatch done = new CountDownLatch(1);
      // System.out.println("PING");
      stub.withDeadlineAfter(10, TimeUnit.SECONDS).ping(PingRequest.newBuilder().build(), new StreamObserver<PingResponse>() {
        @Override
        public void onNext(PingResponse value) {
          // System.out.println("ON_NEXT");
          available.set(true);
        }

        @Override
        public void onError(Throwable t) {
          // System.out.println("ON_ERROR " + t.getMessage());
          done.countDown();
        }

        @Override
        public void onCompleted() {
          // System.out.println("ON_COMPLETE");
          done.countDown();
        }
      });
      Utils.resetIfInterrupted(() -> done.await());
      return available.get();
    }

    @Override
    public void blockUntilConnected(MirrorStub stub) {
      while (!isAvailable(stub)) {
        Utils.resetIfInterrupted(() -> Thread.sleep(durationBetweenDetections.toMillis()));
      }
    }
  }

}

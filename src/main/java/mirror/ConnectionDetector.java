package mirror;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.ManagedChannel;
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

  void blockUntilConnected();

  /** A stub/noop detector for unit tests. */
  public static class Noop implements ConnectionDetector {
    @Override
    public void blockUntilConnected() {
    }
  }

  /** A detector that uses our app-specific PingRequest/PingResponse. */
  public static class Impl implements ConnectionDetector {
    private static final Duration durationBetweenDetections = Duration.ofSeconds(5);
    private static final Duration deadlineDuration = Duration.ofSeconds(5);
    private final ChannelFactory channelFactory;

    public static void main(String[] args) throws Exception {
      ChannelFactory cf = () -> NettyChannelBuilder.forAddress("shaberma-ld1", 49172).negotiationType(NegotiationType.PLAINTEXT).build();
      while (true) {
        new Impl(cf).blockUntilConnected();
        System.out.println("CONNECTED");
        Thread.sleep(5000);
      }
    }

    public Impl(ChannelFactory channelFactory) {
      this.channelFactory = channelFactory;
    }

    @Override
    public void blockUntilConnected() {
      while (!isAvailable()) {
        Utils.resetIfInterrupted(() -> Thread.sleep(durationBetweenDetections.toMillis()));
      }
    }

    private boolean isAvailable() {
      AtomicBoolean available = new AtomicBoolean(false);
      CountDownLatch done = new CountDownLatch(1);
      // We have to get a new channel on each successive attempt, as that's the only
      // way to really make a new connection attempt.
      ManagedChannel c = channelFactory.newChannel();
      try {
        MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");
        stub.withDeadlineAfter(deadlineDuration.toMillis(), MILLISECONDS).ping(PingRequest.newBuilder().build(), new StreamObserver<PingResponse>() {
          @Override
          public void onNext(PingResponse value) {
            available.set(true);
          }

          @Override
          public void onError(Throwable t) {
            done.countDown();
          }

          @Override
          public void onCompleted() {
            done.countDown();
          }
        });
        Utils.resetIfInterrupted(() -> done.await());
        return available.get();
      } finally {
        c.shutdownNow();
      }
    }
  }

}

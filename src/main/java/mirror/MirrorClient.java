package mirror;

import static mirror.Utils.newWatchService;
import static mirror.Utils.withTimeout;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.SettableFuture;

import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorStub;

public class MirrorClient {

  private static final Logger log = LoggerFactory.getLogger(MirrorClient.class);

  public static void main(String[] args) throws Exception {
    LoggingConfig.init();

    String host = args[0];
    Integer port = Integer.parseInt(args[1]);
    Path remoteRoot = Paths.get(args[2]);
    Path localRoot = Paths.get(args[3]);

    Channel c = NettyChannelBuilder.forAddress(host, port).negotiationType(NegotiationType.PLAINTEXT).maxMessageSize(1073741824).build();
    MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");
    MirrorClient client = new MirrorClient(localRoot, remoteRoot, new ConnectionDetector.Impl(), newWatchService());
    client.startSession(stub);

    // TODO something better
    CountDownLatch cl = new CountDownLatch(1);
    cl.await();
  }

  private final Path localRoot;
  private final Path remoteRoot;
  private final ConnectionDetector detector;
  private final WatchService watchService;
  private volatile MirrorSession session;
  private volatile boolean stopped;

  public MirrorClient(Path localRoot, Path remoteRoot, ConnectionDetector detector, WatchService watchService) {
    this.localRoot = localRoot;
    this.remoteRoot = remoteRoot;
    this.detector = detector;
    this.watchService = watchService;
  }

  /** Connects to the server and starts a sync session. */
  public void startSession(MirrorStub stub) {
    detector.blockUntilConnected(stub);
    log.info("Connected, starting session");

    session = new MirrorSession(localRoot.toAbsolutePath(), watchService);
    session.addStoppedCallback(() -> {
      if (!stopped) {
        startSession(stub);
      }
    });

    // 1. see what our current state is
    try {
      List<Update> localState = session.calcInitialState();
      log.info("Client has " + localState.size() + " paths");

      // 2. send it to the server, so they can send back any stale/missing paths we have
      SettableFuture<Integer> sessionId = SettableFuture.create();
      SettableFuture<List<Update>> remoteState = SettableFuture.create();

      // Ideally this would be a blocking/sync call, but it looks like because
      // one of our RPC methods is streaming, then this one is as well
      withTimeout(stub).initialSync(InitialSyncRequest
        .newBuilder() //
        .setRemotePath(remoteRoot.toString())
        .addAllState(localState)
        .build(), new StreamObserver<InitialSyncResponse>() {
          @Override
          public void onNext(InitialSyncResponse value) {
            sessionId.set(value.getSessionId());
            remoteState.set(value.getStateList());
          }

          @Override
          public void onError(Throwable t) {
            log.error("Error from incoming server stream", t);
            session.stop();
          }

          @Override
          public void onCompleted() {
          }
        });

      session.addInitialRemoteUpdates(remoteState.get());
      log.info("Server has " + remoteState.get().size() + " paths");

      StreamObserver<Update> incomingChanges = new StreamObserver<Update>() {
        @Override
        public void onNext(Update update) {
          session.addRemoteUpdate(update);
        }

        @Override
        public void onError(Throwable t) {
          log.error("Error from incoming server stream", t);
          session.stop();
        }

        @Override
        public void onCompleted() {
          log.info("onCompleted called on client incoming stream");
          session.stop();
        }
      };

      // StreamObserver<Update> outgoingChanges = stub.streamUpdates(incomingChanges);
      ClientCall<Update, Update> call = stub.getChannel().newCall(MirrorGrpc.METHOD_STREAM_UPDATES, stub.getCallOptions());
      CallStreamObserver<Update> outgoingChanges = new ClientCallToCallStreamAdapter<>(call, incomingChanges);

      // send over the sessionId as a fake update
      outgoingChanges.onNext(Update.newBuilder().setPath(sessionId.get().toString()).build());

      session.diffAndStartPolling(new BlockingStreamObserver<Update>(outgoingChanges));
    } catch (Exception e) {
      log.error("Exception starting the client", e);
      session.stop();
    }
  }

  public void stop() throws InterruptedException, IOException {
    stopped = true;
    session.stop();
  }
}

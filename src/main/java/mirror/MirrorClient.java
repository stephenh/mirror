package mirror;

import static mirror.Utils.withTimeout;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.SettableFuture;

import io.grpc.Channel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
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
    MirrorClient client = new MirrorClient(localRoot, remoteRoot, new ConnectionDetector.Impl(), FileSystems.getDefault());
    client.startSession(stub);

    // TODO something better
    CountDownLatch cl = new CountDownLatch(1);
    cl.await();
  }

  private final Path localRoot;
  private final Path remoteRoot;
  private final ConnectionDetector detector;
  private final FileSystem fileSystem;
  private volatile MirrorSession session;
  private volatile boolean stopped;

  public MirrorClient(Path localRoot, Path remoteRoot, ConnectionDetector detector, FileSystem fileSystem) {
    this.localRoot = localRoot;
    this.remoteRoot = remoteRoot;
    this.detector = detector;
    this.fileSystem = fileSystem;
  }

  /** Connects to the server and starts a sync session. */
  public void startSession(MirrorStub stub) {
    detector.blockUntilConnected(stub);
    log.info("Connected, starting session");

    session = new MirrorSession(localRoot.toAbsolutePath(), fileSystem);

    // Automatically re-connect when we're disconnected
    session.addStoppedCallback(() -> {
      if (!stopped) {
        startSession(stub);
      }
    });

    // grpc's deadlines/timeouts don't work well with streams (currently/AFAICT),
    // so if the server/ goes away, we have no way of knowing. so for now we ping.
    final Timer task = new Timer();
    session.addStoppedCallback(() -> task.cancel());
    task.schedule(new TimerTask() {
      public void run() {
        if (!detector.isAvailable(stub)) {
          log.info("Connection lost, killing session");
          session.stop();
          task.cancel();
        }
      }
    }, 30_000, 30_000);

    // 1. see what our current state is
    try {
      List<Update> localState = session.calcInitialState();
      log.info("Client has " + localState.size() + " paths");

      // 2. send it to the server, so they can send back any stale/missing paths we have
      SettableFuture<Integer> sessionId = SettableFuture.create();
      SettableFuture<List<Update>> remoteState = SettableFuture.create();

      // Ideally this would be a blocking/sync call, but it looks like because
      // one of our RPC methods is streaming, then this one is as well
      InitialSyncRequest req = InitialSyncRequest.newBuilder().setRemotePath(remoteRoot.toString()).addAllState(localState).build();
      withTimeout(stub).initialSync(req, new StreamObserver<InitialSyncResponse>() {
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

      log.info("Server has " + remoteState.get().size() + " paths");
      session.addInitialRemoteUpdates(remoteState.get());
      log.info("Tree populated");

      AtomicReference<StreamObserver<Update>> outgoingChangesRef = new AtomicReference<>();
      ClientResponseObserver<Update, Update> incomingChanges = new ClientResponseObserver<Update, Update>() {
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

        @Override
        public void beforeStart(ClientCallStreamObserver<Update> outgoingChanges) {
          // we instantiate the BlockingStreamObserver here before startCall is called
          // so that setOnReadyHandler is not frozen yet
          outgoingChangesRef.set(new BlockingStreamObserver<Update>(outgoingChanges));
        }
      };

      // we ignore the return value because we capture it in the observer
      stub.streamUpdates(incomingChanges);
      StreamObserver<Update> outgoingChanges = outgoingChangesRef.get();

      // send over the sessionId as a fake update
      outgoingChanges.onNext(Update.newBuilder().setPath(sessionId.get().toString()).build());

      session.diffAndStartPolling(outgoingChanges);
    } catch (Exception e) {
      log.error("Exception starting the client", e);
      session.stop();
    }
  }

  public void stop() {
    stopped = true;
    session.stop();
  }
}

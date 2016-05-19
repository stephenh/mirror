package mirror;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    Path root = Paths.get(args[0]).toAbsolutePath();
    String host = args[1];
    Integer port = Integer.parseInt(args[2]);
    Channel c = NettyChannelBuilder.forAddress(host, port).negotiationType(NegotiationType.PLAINTEXT).maxMessageSize(1073741824).build();
    MirrorStub stub = MirrorGrpc.newStub(c).withCompression("gzip");
    MirrorClient client = new MirrorClient(root);
    client.startSession(stub);
    // TODO something better
    CountDownLatch cl = new CountDownLatch(1);
    cl.await();
  }

  private final Path root;
  private MirrorSession session;

  public MirrorClient(Path root) {
    this.root = root;
  }

  /** Connects to the server and starts a sync session. */
  public void startSession(MirrorStub stub) {
    session = new MirrorSession(root);

    try {
      // 1. see what our current state is
      List<Update> localState = session.calcInitialState();
      log.info("Client has " + localState.size() + " paths");

      // 2. send it to the server, so they can send back any stale/missing paths we have
      SettableFuture<List<Update>> remoteState = SettableFuture.create();
      stub.initialSync(InitialSyncRequest.newBuilder().addAllState(localState).build(), new StreamObserver<InitialSyncResponse>() {
        @Override
        public void onNext(InitialSyncResponse value) {
          remoteState.set(value.getStateList());
        }

        @Override
        public void onError(Throwable t) {
          log.error("Error from incoming server stream", t);
        }

        @Override
        public void onCompleted() {
          log.info("onCompleted called on the client incoming stream");
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
        }

        @Override
        public void onCompleted() {
          log.info("onCompleted called on client incoming stream");
        }
      };

      // StreamObserver<Update> outgoingChanges = stub.streamUpdates(incomingChanges);

      ClientCall<Update, Update> call = stub.getChannel().newCall(MirrorGrpc.METHOD_STREAM_UPDATES, stub.getCallOptions());

      CallStreamObserver<Update> outgoingChanges = new ClientCallToCallStreamAdapter<>(call, incomingChanges);

      session.diffAndStartPolling(new BlockingStreamObserver<Update>(outgoingChanges));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void stop() throws InterruptedException, IOException {
    session.stop();
  }
}

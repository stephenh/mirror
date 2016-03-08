package mirror;

import java.nio.file.Path;
import java.util.List;

import com.google.common.util.concurrent.SettableFuture;

import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorStub;

public class MirrorClient {

  private final Path root;
  private MirrorSession session;

  public MirrorClient(Path root) {
    this.root = root;
  }

  /** Connects to the server and starts a sync session. */
  public void startSession(MirrorStub stub) {
    session = new MirrorSession(root);

    try {
      List<Update> state = session.calcInitialState();

      SettableFuture<List<Update>> remoteState = SettableFuture.create();
      stub.initialSync(InitialSyncRequest.newBuilder().addAllState(state).build(), new StreamObserver<InitialSyncResponse>() {
        @Override
        public void onNext(InitialSyncResponse value) {
          remoteState.set(value.getStateList());
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
      });
      session.setRemoteState(remoteState.get());

      StreamObserver<Update> incomingChanges = new StreamObserver<Update>() {
        @Override
        public void onNext(Update update) {
          System.out.println("Received from server " + update);
          session.enqueue(update);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
      };

      StreamObserver<Update> outgoingChanges = stub.connect(incomingChanges);

      session.startPolling(outgoingChanges);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void stop() throws InterruptedException {
    session.stop();
  }
}

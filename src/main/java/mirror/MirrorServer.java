package mirror;

import java.nio.file.Path;
import java.util.List;

import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.Mirror;

public class MirrorServer implements Mirror {

  private final Path root;
  private MirrorSession currentSession = null;

  public MirrorServer(Path root) {
    this.root = root;
  }

  @Override
  public void initialSync(InitialSyncRequest request, StreamObserver<InitialSyncResponse> responseObserver) {
    // start a new session
    // TODO handle if there is an existing session
    currentSession = new MirrorSession(root);
    try {
      // record the client's current state
      currentSession.setRemoteState(request.getStateList());
      // send back our current state
      List<Update> state = currentSession.calcInitialState();
      responseObserver.onNext(InitialSyncResponse.newBuilder().addAllState(state).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public StreamObserver<Update> connect(StreamObserver<Update> outgoingUpdates) {
    try {
      // make an observable for when the client sends in new updates
      StreamObserver<Update> incomingUpdates = new StreamObserver<Update>() {
        @Override
        public void onNext(Update value) {
          System.out.println("Received from client " + value);
          currentSession.enqueue(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
          outgoingUpdates.onCompleted();
        }
      };

      currentSession.startPolling(outgoingUpdates);

      return incomingUpdates;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}

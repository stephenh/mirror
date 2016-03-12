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
      // get our current state
      List<Update> serverState = currentSession.calcInitialState();
      // record the client's current state
      currentSession.setInitialRemoteState(new PathState(request.getStateList()));
      currentSession.seedQueueForInitialSync(new PathState(serverState));
      // send back our state for the client to seed their own sync queue with our missing/stale paths
      responseObserver.onNext(InitialSyncResponse.newBuilder().addAllState(serverState).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public StreamObserver<Update> streamUpdates(StreamObserver<Update> outgoingUpdates) {
    try {
      // make an observable for when the client sends in new updates
      StreamObserver<Update> incomingUpdates = new StreamObserver<Update>() {
        @Override
        public void onNext(Update value) {
          System.out.println("Received from client " + value);
          currentSession.addRemoteUpdate(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
          outgoingUpdates.onCompleted();
        }
      };

      // look for file system updates to send back to the client
      currentSession.startPolling(outgoingUpdates);

      return incomingUpdates;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}

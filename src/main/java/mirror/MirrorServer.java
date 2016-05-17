package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.internal.ServerImpl;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.Mirror;

public class MirrorServer implements Mirror {

  private static final Logger log = LoggerFactory.getLogger(MirrorServer.class);
  private final Path root;
  private MirrorSession currentSession = null;

  public static void main(String[] args) throws Exception {
    LoggingConfig.init();
    Path root = Paths.get(args[0]).toAbsolutePath();
    Integer port = Integer.parseInt(args[1]);
    ServerImpl rpc = NettyServerBuilder.forPort(port).maxMessageSize(1073741824).addService(MirrorGrpc.bindService(new MirrorServer(root))).build();
    rpc.start();
    rpc.awaitTermination();
  }

  public MirrorServer(Path root) {
    this.root = root;
  }

  @Override
  public synchronized void initialSync(InitialSyncRequest request, StreamObserver<InitialSyncResponse> responseObserver) {
    // start a new session
    // TODO handle if there is an existing session
    currentSession = new MirrorSession("[server]", root);
    log.info("Starting new session");
    try {
      // get our current state
      List<Update> serverState = currentSession.calcInitialState();
      log.info("Server has " + serverState.size() + " paths");
      log.info("Client has " + request.getStateList().size() + " paths");
      // record the client's current state
      currentSession.addInitialRemoteUpdates(request.getStateList());
      // send back our state for the client to seed their own sync queue with our missing/stale paths
      responseObserver.onNext(InitialSyncResponse.newBuilder().addAllState(serverState).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized StreamObserver<Update> streamUpdates(StreamObserver<Update> outgoingUpdates) {
    try {
      // make an observable for when the client sends in new updates
      StreamObserver<Update> incomingUpdates = new StreamObserver<Update>() {
        @Override
        public void onNext(Update value) {
          currentSession.addRemoteUpdate(value);
        }

        @Override
        public void onError(Throwable t) {
          log.error("Error from incoming client stream", t);
          outgoingUpdates.onCompleted();
        }

        @Override
        public void onCompleted() {
          log.info("onCompleted called on the server incoming stream");
          outgoingUpdates.onCompleted();
        }
      };
      // look for file system updates to send back to the client
      // currentSession.diffAndStartPolling(new BlockingStreamObserver<Update>(outgoingUpdates));
      currentSession.diffAndStartPolling(outgoingUpdates);
      return incomingUpdates;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}

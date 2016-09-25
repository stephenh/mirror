package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorImplBase;
import mirror.tasks.ThreadBasedTaskFactory;

/**
 * Listens for incoming clients and sets up {@link MirrorSession}s.
 *
 * In theory we can juggle multiple sessions, potentially even to the same destination
 * path, and things should just work (the sessions won't communicate directly, but instead
 * will see each other's file writes just like any other writer).
 */
public class MirrorServer extends MirrorImplBase {

  /**
   * Currently grpc-java doesn't return compressed responses, even if the client
   * has sent a compressed payload. This turns on gzip compression for all responses.
   */
  public static ServerServiceDefinition withCompressionEnabled(BindableService service) {
    return ServerInterceptors.intercept(service, new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        call.setCompression("gzip");
        return next.startCall(call, headers);
      }
    });
  }

  private static final Logger log = LoggerFactory.getLogger(MirrorServer.class);
  private final Map<Integer, MirrorSession> sessions = new HashMap<>();
  private final FileWatcherFactory watcherFactory;
  private int nextSessionId = 1;

  public MirrorServer(FileWatcherFactory watcherFactory) {
    this.watcherFactory = watcherFactory;
  }

  @Override
  public synchronized void initialSync(InitialSyncRequest request, StreamObserver<InitialSyncResponse> responseObserver) {
    int sessionId = nextSessionId++;
    Path root = Paths.get(request.getRemotePath()).toAbsolutePath();
    PathRules includes = new PathRules(request.getIncludesList());
    PathRules excludes = new PathRules(request.getExcludesList());

    log.info("Starting new session " + sessionId + " for + " + root);
    FileWatcher watcher = watcherFactory.newWatcher(root);
    MirrorSession session = new MirrorSession(new ThreadBasedTaskFactory(), root, includes, excludes, watcher);

    sessions.put(sessionId, session);
    session.addStoppedCallback(() -> {
      sessions.remove(sessionId);
    });

    try {
      // get our current state
      List<Update> serverState = session.calcInitialState();
      log.info("Server has " + serverState.size() + " paths");
      log.info("Client has " + request.getStateList().size() + " paths");

      // record the client's current state
      session.addInitialRemoteUpdates(request.getStateList());
      log.info("Tree populated");

      // send back our state for the client to seed their own sync queue with our missing/stale paths
      responseObserver.onNext(InitialSyncResponse.newBuilder().setSessionId(sessionId).addAllState(serverState).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("Error in initialSync", e);
      session.stop();
      responseObserver.onCompleted();
    }
  }

  @Override
  public synchronized StreamObserver<Update> streamUpdates(StreamObserver<Update> _outgoingUpdates) {
    // this is kind of odd, but we don't know the right session for this
    // call until we get the first streaming update (grpc doesn't allow
    // a method call with both unary+streaming arguments).
    final AtomicReference<Integer> sessionId = new AtomicReference<>();
    final AtomicReference<MirrorSession> session = new AtomicReference<>();
    final StreamObserver<Update> outgoingUpdates = new BlockingStreamObserver<Update>((CallStreamObserver<Update>) _outgoingUpdates);
    // make an observable for when the client sends in new updates
    return new StreamObserver<Update>() {
      @Override
      public void onNext(Update value) {
        if (session.get() == null) {
          // this is the first update, which is a dummy value with our session id
          sessionId.set(Integer.parseInt(value.getPath()));
          MirrorSession ms = sessions.get(sessionId.get());
          session.set(ms);
          // look for file system updates to send back to the client
          ms.diffAndStartPolling(outgoingUpdates);
        } else {
          session.get().addRemoteUpdate(value);
        }
      }

      @Override
      public void onError(Throwable t) {
        log.error("Error from incoming client stream", t);
        stopSession();
      }

      @Override
      public void onCompleted() {
        log.info("onCompleted called on the server incoming stream");
        stopSession();
      }

      private void stopSession() {
        if (session.get() != null) {
          session.get().stop();
        }
      }
    };
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    responseObserver.onNext(PingResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

}

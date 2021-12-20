package mirror;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorImplBase;
import mirror.tasks.TaskFactory;

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
  public static class EnableCompressionInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      call.setCompression("gzip");
      return next.startCall(call, headers);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(MirrorServer.class);
  private final Map<String, MirrorSession> sessions = new HashMap<>();
  private final TaskFactory taskFactory;
  private final FileWatcherFactory watcherFactory;
  private final FileAccessFactory fileAccessFactory;
  private final FileAccess root;

  public MirrorServer(TaskFactory taskFactory, FileAccessFactory fileAccessFactory, FileWatcherFactory watcherFactory) {
    this(taskFactory, fileAccessFactory, watcherFactory, fileAccessFactory.newFileAccess(Paths.get("./")));
  }

  public MirrorServer(TaskFactory taskFactory, FileAccessFactory fileAccessFactory, FileWatcherFactory watcherFactory, FileAccess root) {
    this.taskFactory = taskFactory;
    this.fileAccessFactory = fileAccessFactory;
    this.watcherFactory = watcherFactory;
    this.root = root;
  }

  @Override
  public synchronized void timeCheck(TimeCheckRequest request, StreamObserver<TimeCheckResponse> responseObserver) {
    sendErrorIfClockDriftExists(request, responseObserver);
  }

  @Override
  public synchronized void initialSync(InitialSyncRequest request, StreamObserver<InitialSyncResponse> responseObserver) {
    if (sendErrorIfRequestedPathDoesNotExist(request, responseObserver)) {
      return;
    }

    MirrorPaths paths = new MirrorPaths(
      Paths.get(request.getRemotePath()).toAbsolutePath(),
      null,
      new PathRules(request.getIncludesList()),
      new PathRules(request.getExcludesList()),
      request.getDebugAll(),
      request.getDebugPrefixesList());

    String sessionId = request.getRemotePath() + ":" + request.getClientId();
    if (sessions.get(sessionId) != null) {
      log.info("Stopping prior session " + sessionId);
      sessions.get(sessionId).stop();
    }

    //This is the sync direction from the client's point of view. We need to use the complement to construct the session.
    SyncDirection syncDirection = getSyncDirection(request);

    log.info("Starting new session " + sessionId);
    MirrorSession session = new MirrorSession(
            taskFactory,
            paths,
            fileAccessFactory.newFileAccess(paths.root.toAbsolutePath()),
            watcherFactory,
            syncDirection.getComplement());

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

      InitialSyncResponse.Builder response = InitialSyncResponse.newBuilder().setSessionId(sessionId).addAllState(serverState);

      if (!StringUtils.isEmpty(request.getVersion()) && !request.getVersion().equals(Mirror.getVersion())) {
        String warningMessage = String.format("Server version %s does not match client version %s", Mirror.getVersion(), request.getVersion());
        log.warn(warningMessage + " for client " + request.getClientId());
        response.addWarningMessages(warningMessage);
      }

      // send back our state for the client to seed their own sync queue with our missing/stale paths
      responseObserver.onNext(response.build());
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
    final AtomicReference<MirrorSession> session = new AtomicReference<>();
    final StreamObserver<Update> outgoingChanges = new BlockingStreamObserver<Update>((CallStreamObserver<Update>) _outgoingUpdates);
    // make an observable for when the client sends in new updates
    return new StreamObserver<Update>() {
      @Override
      public void onNext(Update value) {
        if (session.get() == null) {
          // this is the first update, which is a dummy value with our session id
          MirrorSession ms = sessions.get(value.getPath());
          session.set(ms);
          // look for file system updates to send back to the client
          ms.diffAndStartPolling(new OutgoingConnectionImpl(outgoingChanges));
        } else {
          session.get().addRemoteUpdate(value);
        }
      }

      @Override
      public void onError(Throwable t) {
        Utils.logConnectionError(log, t);
        stopSession();
      }

      @Override
      public void onCompleted() {
        log.info("Connection completed");
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

  @VisibleForTesting
  int numberOfSessions() {
    return sessions.size();
  }

  private boolean sendErrorIfRequestedPathDoesNotExist(InitialSyncRequest request, StreamObserver<InitialSyncResponse> responseObserver) {
    if (!root.exists(Paths.get(request.getRemotePath()))) {
      String errorMessage = "Path " + request.getRemotePath() + " does not exist on the server";
      log.error(errorMessage + " for " + request.getClientId());
      responseObserver.onNext(InitialSyncResponse.newBuilder().setErrorMessage(errorMessage).build());
      responseObserver.onCompleted();
      return true;
    }
    return false;
  }

  private void sendErrorIfClockDriftExists(TimeCheckRequest request, StreamObserver<TimeCheckResponse> responseObserver) {
    long ourTime = System.currentTimeMillis();
    long clientTime = request.getCurrentTime();
    long driftInMillis = Math.abs(ourTime - clientTime);
    if (clientTime > 0 && driftInMillis > 10_000) {
      String errorMessage = "The client and server clocks are "
        + driftInMillis
        + "ms out of sync, please use ntp/etc. to fix this drift before using mirror";
      log.error(errorMessage + " for " + request.getClientId());
      responseObserver.onNext(TimeCheckResponse.newBuilder().setErrorMessage(errorMessage).build());
    } else {
      responseObserver.onNext(TimeCheckResponse.newBuilder().build());
    }
    responseObserver.onCompleted();
  }

  private SyncDirection getSyncDirection(InitialSyncRequest request) {
    if (request.getAllowOutbound() && !request.getAllowInbound()) {
      return SyncDirection.OUTBOUND;
    }
    if (!request.getAllowOutbound() && request.getAllowInbound()) {
      return SyncDirection.INBOUND;
    }
    return SyncDirection.BOTH;
  }
}

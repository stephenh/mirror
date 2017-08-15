package mirror;

import static mirror.Utils.withTimeout;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.SettableFuture;

import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import mirror.MirrorGrpc.MirrorStub;
import mirror.tasks.TaskFactory;
import mirror.tasks.TaskLogic;

public class MirrorClient {

  private static final Logger log = LoggerFactory.getLogger(MirrorClient.class);

  private final MirrorPaths paths;
  private final TaskFactory taskFactory;
  private final ConnectionDetector detector;
  private final FileWatcherFactory watcherFactory;
  private final FileAccess fileAccess;
  private final ChannelFactory channelFactory;
  private volatile TaskLogic sessionStarter;
  private volatile MirrorSession session;

  public MirrorClient(
    MirrorPaths paths,
    TaskFactory taskFactory,
    ConnectionDetector detector,
    FileWatcherFactory watcherFactory,
    FileAccess fileAccess,
    ChannelFactory channelFactory) {
    this.paths = paths;
    this.taskFactory = taskFactory;
    this.detector = detector;
    this.watcherFactory = watcherFactory;
    this.fileAccess = fileAccess;
    this.channelFactory = channelFactory;
  }

  /** Connects to the server and starts a sync session. */
  public void startSession() throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    sessionStarter = new SessionStarter(channelFactory, started);
    taskFactory.runTask(sessionStarter);
    started.await();
  }

  private void startSession(ChannelFactory channelFactory, CountDownLatch onFailure) {
    detector.blockUntilConnected();
    log.info("Connected, starting session, version " + Mirror.getVersion());

    ManagedChannel channel = channelFactory.newChannel();
    MirrorStub stub = MirrorGrpc.newStub(channel).withCompression("gzip");

    boolean outOfSync = logErrorIfTimeOutOfSync(stub);
    if (outOfSync) {
      return;
    }

    session = new MirrorSession(taskFactory, paths, fileAccess, watcherFactory);
    session.addStoppedCallback(channel::shutdownNow);

    // 1. see what our current state is
    try {
      List<Update> localState = session.calcInitialState();
      log.info("Client has " + localState.size() + " paths");

      // 2. send it to the server, so they can send back any stale/missing paths we have
      SettableFuture<InitialSyncResponse> responseFuture = SettableFuture.create();

      // Ideally this would be a blocking/sync call, but it looks like because
      // one of our RPC methods is streaming, then this one is as well
      InitialSyncRequest req = InitialSyncRequest
        .newBuilder()
        .setRemotePath(paths.remoteRoot.toString())
        .setClientId(getClientId())
        .setVersion(Mirror.getVersion())
        .addAllIncludes(paths.includes.getLines())
        .addAllExcludes(paths.excludes.getLines())
        .addAllDebugPrefixes(paths.debugPrefixes)
        .addAllState(localState)
        .build();
      withTimeout(stub).initialSync(req, new StreamObserver<InitialSyncResponse>() {
        @Override
        public void onNext(InitialSyncResponse value) {
          responseFuture.set(value);
        }

        @Override
        public void onError(Throwable t) {
          Utils.logConnectionError(log, t);
          session.stop();
        }

        @Override
        public void onCompleted() {
          // Purposefully don't stop the session because our IntegrationTests
          // our 1st SyncLogic loop may not have even started/completed yet
          // session.stop();
        }
      });

      InitialSyncResponse response = responseFuture.get();
      if (response.getErrorMessage() != null && !response.getErrorMessage().isEmpty()) {
        log.error(response.getErrorMessage());
        session.stop();
        return;
      }
      for (String warningMessage : response.getWarningMessagesList()) {
        log.warn(warningMessage);
      }

      String sessionId = response.getSessionId();
      List<Update> remoteState = response.getStateList();
      log.info("Server has " + remoteState.size() + " paths");
      session.addInitialRemoteUpdates(remoteState);
      log.info("Tree populated");

      AtomicReference<StreamObserver<Update>> outgoingChangesRef = new AtomicReference<>();
      ClientResponseObserver<Update, Update> incomingChanges = new ClientResponseObserver<Update, Update>() {
        @Override
        public void onNext(Update update) {
          session.addRemoteUpdate(update);
        }

        @Override
        public void onError(Throwable t) {
          Utils.logConnectionError(log, t);
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
      outgoingChanges.onNext(Update.newBuilder().setPath(sessionId).build());

      session.diffAndStartPolling(new OutgoingConnectionImpl(outgoingChanges));

      // Automatically re-connect when we're disconnected
      session.addStoppedCallback(() -> {
        // Don't call startSession again directly, because then we'll start running
        // our connection code on whatever thread is running this callback. Instead
        // just signal our main client thread that it should try again.
        onFailure.countDown();
      });
    } catch (Exception e) {
      log.error("Exception starting the client", e);
      session.stop();
    }
  }

  public void stop() {
    taskFactory.stopTask(sessionStarter);
    session.stop();
  }

  private class SessionStarter implements TaskLogic {
    private final ChannelFactory channelFactory;
    private final CountDownLatch started;

    private SessionStarter(ChannelFactory channelFactory, CountDownLatch started) {
      this.channelFactory = channelFactory;
      this.started = started;
    }

    @Override
    public Duration runOneLoop() throws InterruptedException {
      CountDownLatch onFailure = new CountDownLatch(1);
      startSession(channelFactory, onFailure);
      started.countDown();
      onFailure.await();
      return null;
    }
  }

  private boolean logErrorIfTimeOutOfSync(MirrorStub stub) {
    // 0. Do a time drift check
    SettableFuture<TimeCheckResponse> timeResponse = SettableFuture.create();
    stub.timeCheck(
      TimeCheckRequest.newBuilder().setClientId(getClientId()).setCurrentTime(System.currentTimeMillis()).build(),
      new StreamObserver<TimeCheckResponse>() {
        @Override
        public void onNext(TimeCheckResponse value) {
          timeResponse.set(value);
        }

        @Override
        public void onError(Throwable t) {
          timeResponse.setException(t);
        }

        @Override
        public void onCompleted() {
        }
      });
    try {
      String errorMessage = timeResponse.get().getErrorMessage();
      if (errorMessage != null && !errorMessage.isEmpty()) {
        log.error(errorMessage);
        return true;
      }
      return false;
    } catch (InterruptedException e1) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e1);
    } catch (ExecutionException e1) {
      throw new RuntimeException(e1);
    }
  }

  private static String getClientId() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "unknown";
    }
  }
}

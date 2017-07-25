package mirror;

import static mirror.Utils.withTimeout;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.SettableFuture;

import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
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
  private volatile TaskLogic sessionStarter;
  private volatile MirrorSession session;

  public MirrorClient(MirrorPaths paths, TaskFactory taskFactory, ConnectionDetector detector, FileWatcherFactory watcherFactory) {
    this.paths = paths;
    this.taskFactory = taskFactory;
    this.detector = detector;
    this.watcherFactory = watcherFactory;
  }

  /** Connects to the server and starts a sync session. */
  public void startSession(MirrorStub stub) throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    sessionStarter = new SessionStarter(stub, started);
    taskFactory.runTask(sessionStarter);
    started.await();
  }

  private void startSession(MirrorStub stub, CountDownLatch onFailure) {
    detector.blockUntilConnected();
    log.info("Connected, starting session, version " + Mirror.getVersion());

    session = new MirrorSession(taskFactory, paths, watcherFactory);

    // 1. see what our current state is
    try {
      List<Update> localState = session.calcInitialState();
      log.info("Client has " + localState.size() + " paths");

      // 2. send it to the server, so they can send back any stale/missing paths we have
      SettableFuture<String> sessionId = SettableFuture.create();
      SettableFuture<List<Update>> remoteState = SettableFuture.create();

      // Ideally this would be a blocking/sync call, but it looks like because
      // one of our RPC methods is streaming, then this one is as well
      InitialSyncRequest req = InitialSyncRequest 
        .newBuilder()
        .setRemotePath(paths.remoteRoot.toString())
        .setClientId(InetAddress.getLocalHost().getHostName())
        .addAllIncludes(paths.includes.getLines())
        .addAllExcludes(paths.excludes.getLines())
        .addAllDebugPrefixes(paths.debugPrefixes)
        .addAllState(localState)
        .build();
      withTimeout(stub).initialSync(req, new StreamObserver<InitialSyncResponse>() {
        @Override
        public void onNext(InitialSyncResponse value) {
          sessionId.set(value.getSessionId());
          remoteState.set(value.getStateList());
        }

        @Override
        public void onError(Throwable t) {
          if (t instanceof StatusRuntimeException) {
            log.info("Connection status " + ((StatusRuntimeException) t).getStatus());
          } else if (t instanceof StatusException) {
            log.info("Connection status " + ((StatusException) t).getStatus());
          } else {
            log.error("Error from incoming client stream", t);
          }
          session.stop();
        }

        @Override
        public void onCompleted() {
          // Purposefully don't stop the session because our IntegrationTests
          // our 1st SyncLogic loop may not have even started/completed yet
          // session.stop();
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
      outgoingChanges.onNext(Update.newBuilder().setPath(sessionId.get()).build());

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
    private final MirrorStub stub;
    private final CountDownLatch started;

    private SessionStarter(MirrorStub stub, CountDownLatch started) {
      this.stub = stub;
      this.started = started;
    }

    @Override
    public Duration runOneLoop() throws InterruptedException {
      CountDownLatch onFailure = new CountDownLatch(1);
      startSession(stub, onFailure);
      started.countDown();
      onFailure.await();
      return null;
    }
  }
}

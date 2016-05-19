package mirror;

import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * Provides a client-side {@link CallStreamObserver}.
 *
 * The {@link CallStreamObserver} exposes {@code isReady} and {@code setOnReadyHandler}
 * so that writers can tell if the stream buffers are becoming full, and avoid continuing
 * to call {@code onNext}.
 *
 * See {@link BlockingStreamObserver}.
 */
class ClientCallToCallStreamAdapter<ReqT, RespT> extends CallStreamObserver<ReqT> {

  private final AtomicReference<Runnable> onReady = new AtomicReference<>();
  private final ClientCall<ReqT, RespT> call;

  ClientCallToCallStreamAdapter(ClientCall<ReqT, RespT> call, StreamObserver<RespT> incoming) {
    this.call = call;
    call.start(new ClientCall.Listener<RespT>() {
      @Override
      public void onMessage(RespT message) {
        incoming.onNext(message);
        call.request(1);
      }

      @Override
      public void onReady() {
        Runnable r = onReady.get();
        if (r != null) {
          r.run();
        }
      }
    }, new Metadata());
    call.request(1);
  }

  @Override
  public void onNext(ReqT value) {
    call.sendMessage(value);
  }

  @Override
  public void onError(Throwable t) {
    call.cancel("Cancel because of onError", t);
  }

  @Override
  public void onCompleted() {
    call.halfClose();
  }

  @Override
  public boolean isReady() {
    return call.isReady();
  }

  @Override
  public void setOnReadyHandler(Runnable onReadyHandler) {
    onReady.set(onReadyHandler);
  }

  @Override
  public void disableAutoInboundFlowControl() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void request(int count) {
    call.request(count);
  }
}

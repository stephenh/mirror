package mirror;

import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * Provides flow control feedback to the application by blocking on {@code onNext}
 * if the underlying {@link CallStreamObserver} is not ready.
 */
class BlockingStreamObserver<T> implements StreamObserver<T> {

  private final CallStreamObserver<T> delegate;
  private final Object lock = new Object();

  BlockingStreamObserver(CallStreamObserver<T> delegate) {
    this.delegate = delegate;
    this.delegate.setOnReadyHandler(() -> {
      synchronized (lock) {
        lock.notifyAll(); // wake up our thread
        System.out.println("NOW READY");
      }
    });
  }

  @Override
  public void onNext(T value) {
    synchronized (lock) {
      while (!delegate.isReady()) {
        System.out.println("NOT READY");
        try {
          lock.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
    delegate.onNext(value);
  }

  @Override
  public void onError(Throwable t) {
    delegate.onError(t);
  }

  @Override
  public void onCompleted() {
    delegate.onCompleted();
  }
}

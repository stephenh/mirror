package mirror;

import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * Provides flow control feedback to the application by blocking on {@code onNext}
 * if the underlying {@link CallStreamObserver} is not ready.
 *
 * Typically blocking a thread is considered bad form, e.g. you could block your client
 * UI thread or your server-side request-serving thread, but for Mirror's purposes, we
 * only write to BlockingStreamObserver from our own dedicated application threads. These
 * application threads are typically processing a queue, so blocking is actually what we
 * want to do, as then the work will build up in the queue.
 */
class BlockingStreamObserver<T> implements StreamObserver<T> {

  private final CallStreamObserver<T> delegate;
  private final Object lock = new Object();

  BlockingStreamObserver(CallStreamObserver<T> delegate) {
    this.delegate = delegate;
    final Runnable notifyAll = () -> {
      synchronized (lock) {
        lock.notifyAll(); // wake up our thread
      }
    };
    this.delegate.setOnReadyHandler(notifyAll);
    if (delegate instanceof ServerCallStreamObserver) {
      ((ServerCallStreamObserver<T>) delegate).setOnCancelHandler(notifyAll);
    }
  }

  @Override
  public void onNext(T value) {
    synchronized (lock) {
      // in theory we could implement ServerCallStreamObserver and expose isCancelled to our client,
      // but for current purposes we only need the StreamObserver API, so treat a cancelled observer
      // as something we just want to un-block from and return, and we'll trust the rest of our session
      // to shutdown accordingly.
      if (delegate instanceof ServerCallStreamObserver && ((ServerCallStreamObserver<T>) delegate).isCancelled()) {
        return;
      }
      while (!delegate.isReady()) {
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

package mirror;

import java.util.ArrayList;
import java.util.List;

import io.grpc.stub.StreamObserver;

public class StubObserver<T> implements StreamObserver<T> {

  protected final List<T> values = new ArrayList<>();
  private boolean completed;

  @Override
  public void onNext(T value) {
    if (completed) {
      throw new IllegalStateException();
    }
    values.add(value);
  }

  @Override
  public void onError(Throwable t) {
    if (completed) {
      throw new IllegalStateException();
    }
    completed = true;
  }

  @Override
  public void onCompleted() {
    completed = true;
  }
}
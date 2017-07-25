package mirror;

import io.grpc.stub.StreamObserver;

public class OutgoingConnectionImpl implements OutgoingConnection {
  
  private volatile StreamObserver<Update> outgoingChanges;

  public OutgoingConnectionImpl(StreamObserver<Update> outgoingChanges) {
    this.outgoingChanges = outgoingChanges;
  }

  @Override
  public void send(Update update) {
    outgoingChanges.onNext(update);
  }

  @Override
  public boolean isConnected() {
    return outgoingChanges != null;
  }

  @Override
  public void awaitReconnected() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void closeConnection() {
    outgoingChanges.onCompleted();
  }

}

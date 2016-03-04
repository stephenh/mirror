import io.grpc.stub.StreamObserver;
import mirror.Empty;
import mirror.MirrorGrpc.Mirror;
import mirror.Update;

public class MirrorServer implements Mirror {

  @Override
  public void connect(Empty request, StreamObserver<Update> responseObserver) {
    responseObserver.onNext(Update.newBuilder().setPath("/a").build());
    responseObserver.onNext(Update.newBuilder().setPath("/b").build());
    responseObserver.onCompleted();
  }

}

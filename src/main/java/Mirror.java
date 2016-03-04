
import java.util.Iterator;

import com.google.common.collect.Lists;

import io.grpc.Channel;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import mirror.Empty;
import mirror.MirrorGrpc;
import mirror.MirrorGrpc.MirrorBlockingClient;
import mirror.Update;

public class Mirror {
  public static void main(String[] args) throws Exception {
    ServerImpl rpc = NettyServerBuilder.forPort(10000).addService(MirrorGrpc.bindService(new MirrorServer())).build();
    rpc.start();
    System.out.println("started");

    Channel c = NettyChannelBuilder.forAddress("localhost", 10000).negotiationType(NegotiationType.PLAINTEXT).build();
    MirrorBlockingClient mc = MirrorGrpc.newBlockingStub(c);
    Iterator<Update> updates = mc.connect(Empty.newBuilder().build());
    for (Update update : Lists.newArrayList(updates)) {
      System.out.println("got " + update);
    }
  }

}

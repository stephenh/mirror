package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.grpc.Channel;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import mirror.MirrorGrpc.MirrorStub;

public class Mirror {
  public static void main(String[] args) throws Exception {
    Path root1 = Paths.get("/home/stephen/dir1");
    Path root2 = Paths.get("/home/stephen/dir2");

    ServerImpl rpc = NettyServerBuilder.forPort(10000).addService(MirrorGrpc.bindService(new MirrorServer(root1))).build();
    rpc.start();
    System.out.println("started server");

    Channel c = NettyChannelBuilder.forAddress("localhost", 10000).negotiationType(NegotiationType.PLAINTEXT).build();
    MirrorStub stub = MirrorGrpc.newStub(c);
    MirrorClient client = new MirrorClient(root2);
    client.connect(stub);

    System.out.println("connected client");

    // just wait for control-c
    rpc.awaitTermination();
  }

}

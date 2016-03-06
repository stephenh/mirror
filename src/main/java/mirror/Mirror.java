package mirror;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.google.common.collect.Lists;

import io.grpc.Channel;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import mirror.MirrorGrpc.MirrorBlockingClient;

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

    Path root = Paths.get("/home/stephen/dir1");
    WatchService watchService = FileSystems.getDefault().newWatchService();
    BlockingQueue<Update> queue = new ArrayBlockingQueue<>(10_000);
    FileWatcher r = new FileWatcher(watchService, root, queue);
    r.performInitialScan();

    List<Update> initial = new ArrayList<>();
    queue.drainTo(initial);
    for (Update u : initial) {
      System.out.println("initial " + u);
    }

    r.startPolling();
    while (true) {
      Update u = queue.take();
      System.out.println("changed " + u);
    }
  }

}

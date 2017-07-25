package mirror;

import io.grpc.ManagedChannel;

/** Creates new channels (connections) to the remote system. */
public interface ChannelFactory {

  ManagedChannel newChannel();

}

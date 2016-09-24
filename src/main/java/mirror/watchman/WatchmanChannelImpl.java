package mirror.watchman;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.buck.bser.BserDeserializer;
import com.facebook.buck.bser.BserSerializer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import joist.util.Execute;
import joist.util.Execute.BufferedResult;

/**
 * Calls watchman via JNR socket channel.
 */
class WatchmanChannelImpl implements Watchman {

  public static void main(String[] args) throws Exception {
    Optional<Watchman> wc = createIfAvailable();
    System.out.println(wc.get());
    System.out.println(wc.get().query("watch-list"));
    System.out.println(wc.get().query("find", "/home/stephen/dir1"));
  }

  public static Optional<Watchman> createIfAvailable() throws IOException {
    BufferedResult r = new Execute("watchman").addEnvPaths().arg("get-sockname").toBuffer();
    if (r.exitValue != 0) {
      return Optional.empty();
    }
    Map<String, String> map = parseToMap(r);
    String socketPath = (String) map.get("sockname");
    UnixSocketAddress address = new UnixSocketAddress(new File(socketPath));
    UnixSocketChannel channel = UnixSocketChannel.open(address);
    return Optional.of(new WatchmanChannelImpl(channel));
  }

  // eventually we'll use bser, but for bootstrapping JSON is fine
  private static Map<String, String> parseToMap(BufferedResult r) {
    Gson gson = new Gson();
    Type type = new TypeToken<Map<String, String>>() {
    }.getType();
    Map<String, String> map = gson.fromJson(r.out, type);
    return map;
  }

  private static final Logger log = LoggerFactory.getLogger(WatchmanChannelImpl.class);
  private final BserSerializer serializer = new BserSerializer();
  private final BserDeserializer deserializer = new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED);
  private final ByteChannel channel;
  private final InputStream output;
  private final OutputStream input;

  public WatchmanChannelImpl(ByteChannel channel) {
    this.channel = channel;
    this.output = Channels.newInputStream(channel);
    this.input = Channels.newOutputStream(channel);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> query(Object... query) throws IOException {
    log.debug("Sending query: %s", query);
    serializer.serializeToStream(Arrays.asList(query), input);
    Object response = deserializer.deserializeBserValue(output);
    if (response == null) {
      throw new IOException("Unrecognized response for: " + query);
    }
    if (log.isTraceEnabled()) {
      log.trace("Got response: %s", response);
    }
    return (Map<String, Object>) response;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

}

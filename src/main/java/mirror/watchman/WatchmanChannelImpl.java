package mirror.watchman;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

import jnr.enxio.channels.NativeSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import joist.util.Execute;
import joist.util.Execute.BufferedResult;

/**
 * Calls watchman via JNR socket channel.
 */
public class WatchmanChannelImpl implements Watchman {

  public static void main(String[] args) throws Exception {
    Optional<Watchman> wc = createIfAvailable();
    System.out.println(wc.get());
    System.out.println(wc.get().query("watch-list"));
    System.out.println(wc.get().query("find", "/home/stephen/dir1"));
  }

  public static Optional<Watchman> createIfAvailable() {
    BufferedResult r;
    try {
      r = new Execute("watchman").addEnvPaths().arg("get-sockname").toBuffer();
      if (r.exitValue != 0) {
        return Optional.empty();
      }
    } catch (RuntimeException e) {
      return Optional.empty(); // watchman not found on the path
    }
    Map<String, String> map = parseToMap(r);
    String socketPath = (String) map.get("sockname");
    UnixSocketAddress address = new UnixSocketAddress(new File(socketPath));
    try {
      UnixSocketChannel channel = UnixSocketChannel.open(address);
      return Optional.of(new WatchmanChannelImpl(channel));
    } catch (IOException e) {
      log.error("Error creating watchman channel, skipping watchman", e);
      return Optional.empty();
    }
  }

  // eventually we'll use bser, but for bootstrapping JSON is fine
  private static Map<String, String> parseToMap(BufferedResult r) {
    return new Gson().fromJson(r.out, new TypeToken<Map<String, String>>() {
    }.getType());
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

  public Map<String, Object> query(Object... query) throws IOException {
    log.debug("Sending query: {}", query);
    serializer.serializeToStream(Arrays.asList(query), input);
    return read();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Map<String, Object> read() throws IOException {
    Object response = deserializer.deserializeBserValue(output);
    if (response == null) {
      throw new IOException("Unrecognized response");
    }
    if (log.isTraceEnabled()) {
      log.trace("Got response: {}", response);
    }
    Map<String, Object> map = (Map<String, Object>) response;
    if (map.containsKey("error")) {
      throw new RuntimeException("watchman error: " + map.get("error"));
    }
    if (map.containsKey("warning")) {
      log.warn((String) map.get("warning"));
    }
    return map;
  }

  @Override
  public void close() throws IOException {
    ((NativeSocketChannel) channel).shutdownInput();
    ((NativeSocketChannel) channel).shutdownOutput();
    channel.close();
  }

}

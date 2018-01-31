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
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

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
    Optional<WatchmanFactory> wc = createIfAvailable();
    Watchman wm = wc.get().newWatchman();
    System.out.println(wm);
    System.out.println(wm.query("watch-list"));
    System.out.println(wm.query("find", "/home/stephen/dir1"));
  }

  public static Optional<WatchmanFactory> createIfAvailable() {
    WatchmanFactory factory = () -> {
      BufferedResult r = new Execute("watchman").addEnvPaths().arg("get-sockname").toBuffer();
      if (r.exitValue != 0) {
        throw new RuntimeException("Non-zero exit value from watchman");
      }
      Map<String, String> map = parseToMap(r);
      String socketPath = (String) map.get("sockname");
      UnixSocketAddress address = new UnixSocketAddress(new File(socketPath));
      UnixSocketChannel channel = UnixSocketChannel.open(address);
      return new WatchmanChannelImpl(channel);
    };
    // probe an initial watchman instance, if it works, return the factory
    try {
      factory.newWatchman().close();
      return Optional.of(factory);
    } catch (Exception e) {
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
  private final Set<String> shownWarnings = new ConcurrentSkipListSet<>();
  private final ByteChannel channel;
  private final InputStream output;
  private final OutputStream input;

  public WatchmanChannelImpl(ByteChannel channel) {
    this.channel = channel;
    this.output = Channels.newInputStream(channel);
    this.input = Channels.newOutputStream(channel);
  }

  public Map<String, Object> query(Object... query) throws IOException {
    log.debug("Sending query: {}", Arrays.toString(query));
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
      if (((String) map.get("error")).contains("IN_Q_OVERFLOW")) {
        throw new WatchmanOverflowException();
      }
      throw new RuntimeException("watchman error: " + map.get("error"));
    }
    if (map.containsKey("warning")) {
      // Currently watchman keeps sending the "overflow happened 1 time"
      // on each PDU, until a watch-del command is issued (which is non-trivial
      // for us to do, as we'd have to restart our watch/subscribe). For now
      // we just show the warning only once and then suppress future warnings.
      String warning = (String) map.get("warning");
      if (!shownWarnings.contains(warning)) {
        log.warn(warning);
        shownWarnings.add(warning);
      }
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

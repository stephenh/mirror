package mirror.watchman;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.watchman.Callback;
import com.facebook.watchman.WatchmanClient;
import com.facebook.watchman.WatchmanClient.SubscriptionDescriptor;
import com.facebook.watchman.WatchmanClientImpl;
import com.facebook.watchman.WatchmanTransport;
import com.facebook.watchman.WatchmanTransportBuilder;

/**
 * A simple implementation of our {@link Watchman} that defers to the real watchman impl.
 *
 * Originally this did hand-written communication with the watchman process, but now
 * it's just a boilerplate mapping to the {@link WatchmanClient}.
 */
public class WatchmanImpl implements Watchman {

  public static void main(String[] args) throws Exception {
    Watchman wm = createIfAvailable().get();
    System.out.println(wm);
    System.out.println(wm.run("watch-list"));
    System.out.println(wm.run("find", "/home/stephen/dir1"));
  }

  public static Optional<Watchman> createIfAvailable() {
    try {
      WatchmanTransport t = WatchmanTransportBuilder.discoverTransport();
      WatchmanClient client = new WatchmanClientImpl(t);
      client.start();
      return Optional.of(new WatchmanImpl(client));
    } catch (Exception e) {
      log.error("Error creating watchman channel, skipping watchman", e);
      return Optional.empty();
    }
  }

  private static final Logger log = LoggerFactory.getLogger(WatchmanImpl.class);
  private final WatchmanClient client;

  private WatchmanImpl(WatchmanClient client) {
    this.client = client;
  }

  @Override
  public SubscriptionDescriptor subscribe(Path path, Map<String, Object> query, Callback listener) {
    try {
      return client.subscribe(path, query, listener).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Map<String, Object> run(Object... query) {
    try {
      return client.run(Arrays.asList(query)).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void unsubscribe(SubscriptionDescriptor descriptor) {
    try {
      client.unsubscribe(descriptor).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() throws Exception {
    client.close();
  }

}

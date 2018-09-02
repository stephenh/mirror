package mirror.watchman;

import java.nio.file.Path;
import java.util.Map;

import com.facebook.watchman.Callback;
import com.facebook.watchman.WatchmanClient;
import com.facebook.watchman.WatchmanClient.SubscriptionDescriptor;

/**
 * Watchman interface.
 * 
 * Technically {@link WatchmanClient} is already an interface, but this predates
 * that and is also a smaller/blocking interface.
 */
public interface Watchman extends AutoCloseable {

  Map<String, Object> run(Object... query);

  SubscriptionDescriptor subscribe(Path path, Map<String, Object> query, Callback listener);

  void unsubscribe(SubscriptionDescriptor descriptor);

}

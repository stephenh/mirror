package mirror;

/**
 * API for sending updates to the remote session.
 *
 * It also knows about whether it's connected, and can block
 * clients until they reconnect.
 */
public interface OutgoingConnection {

  void send(Update update);

  boolean isConnected();

  void awaitReconnected();

  void closeConnection();
  
}

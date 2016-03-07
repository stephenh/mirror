package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.Test;

import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;

public class SyncLogicTest {

  private static final Path fooDotTxt = Paths.get("./foo.txt");
  private final static byte[] data = new byte[] { 1, 2, 3, 4 };
  private final static byte[] data2 = new byte[] { 1, 2, 3, 4, 5, 6 };
  private final BlockingQueue<Update> remoteChanges = new ArrayBlockingQueue<>(10);
  private final BlockingQueue<Update> localChanges = new ArrayBlockingQueue<>(10);
  private final StubObserver<Update> outgoing = new StubObserver();
  private final StubFileAccess fileAccess = new StubFileAccess();
  private final SyncLogic l = new SyncLogic(Paths.get("./"), remoteChanges, localChanges, outgoing, fileAccess);

  @Test
  public void sendLocalChangeToRemote() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes locally
    Update u = Update.newBuilder().setPath("foo.txt").build();
    localChanges.add(u);
    // when we notice
    l.pollLocal();
    // then we sent it to the remote
    assertThat(outgoing.values.size(), is(1));
    // and it has the correct data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getData().toByteArray(), is(data));
    // and the time stamp
    assertThat(sent.getModTime(), is(1L));
  }

  @Test
  public void sendLocalDeleteToRemote() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it is deleted locally
    Update u = Update.newBuilder().setPath("foo.txt").setDelete(true).build();
    localChanges.add(u);
    // when we notice
    l.pollLocal();
    // then we sent it to the remote
    assertThat(outgoing.values.size(), is(1));
    // and we don't need to send any data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getData().size(), is(0));
    // time stamp (?)
    // assertThat(sent.getModTime(), is(1L));
    // and we marked it as a delete
    assertThat(sent.getDelete(), is(true));
  }

  @Test
  public void saveRemoteChangeLocally() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes remotely
    Update u = Update.newBuilder().setPath("foo.txt").setData(ByteString.copyFrom(data2)).setModTime(10L).build();
    remoteChanges.add(u);
    // when we notice
    l.pollRemote();
    // then we've saved it locally
    ByteBuffer data = fileAccess.read(fooDotTxt);
    assertThat(data.array(), is(data2));
    assertThat(fileAccess.getModifiedTime(fooDotTxt), is(10L));
  }

  @Test
  public void saveRemoteDeleteLocally() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it is deleted remotely
    Update u = Update.newBuilder().setPath("foo.txt").setDelete(true).build();
    remoteChanges.add(u);
    // when we notice
    l.pollRemote();
    // then we delete it locally
    assertThat(fileAccess.wasDeleted(fooDotTxt), is(true));
  }

  @Test
  public void doNotEchoRemoteChange() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes remotely
    Update u = Update.newBuilder().setPath("foo.txt").setData(ByteString.copyFrom(data2)).build();
    remoteChanges.add(u);
    // when we notice and save that write locally
    l.pollRemote();
    localChanges.add(u);
    // then we don't echo it back out to the remote
    l.pollLocal();
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  private static class StubObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private boolean completed;

    @Override
    public void onNext(T value) {
      if (completed) {
        throw new IllegalStateException();
      }
      values.add(value);
    }

    @Override
    public void onError(Throwable t) {
      if (completed) {
        throw new IllegalStateException();
      }
      completed = true;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

}

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

  private static final Path fooDotTxt = Paths.get("foo.txt");
  private final static byte[] data = new byte[] { 1, 2, 3, 4 };
  private final static byte[] data2 = new byte[] { 1, 2, 3, 4, 5, 6 };
  private final BlockingQueue<Update> changes = new ArrayBlockingQueue<>(10);
  private final StubObserver<Update> outgoing = new StubObserver<>();
  private final StubFileAccess fileAccess = new StubFileAccess();
  private final SyncLogic l = new SyncLogic(changes, outgoing, fileAccess);

  @Test
  public void sendLocalChangeToRemote() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes locally
    Update u = Update.newBuilder().setPath("foo.txt").setLocal(true).build();
    changes.add(u);
    // when we notice
    l.poll();
    // then we sent it to the remote
    assertThat(outgoing.values.size(), is(1));
    // and it has the correct data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getData().toByteArray(), is(data));
    // and the time stamp
    assertThat(sent.getModTime(), is(1L));
    assertThat(sent.getLocal(), is(false));
  }

  @Test
  public void sendLocalDeleteToRemote() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // that also exists on the remote
    PathState remoteState = new PathState();
    remoteState.record(fooDotTxt, 1L);
    l.addRemoteState(remoteState);
    // and it is deleted locally
    Update u = Update.newBuilder().setPath("foo.txt").setDelete(true).setLocal(true).build();
    changes.add(u);
    // when we notice
    l.poll();
    // then we sent it to the remote
    assertThat(outgoing.values.size(), is(1));
    // and we don't need to send any data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getData().size(), is(0));
    // time stamp (?)
    // assertThat(sent.getModTime(), is(1L));
    // and we marked it as a delete
    assertThat(sent.getDelete(), is(true));
    assertThat(sent.getLocal(), is(false));
  }

  @Test
  public void saveRemoteChangeLocally() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes remotely
    Update u = Update.newBuilder().setPath("foo.txt").setData(ByteString.copyFrom(data2)).setModTime(10L).build();
    changes.add(u);
    // when we notice
    l.poll();
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
    changes.add(u);
    // when we notice
    l.poll();
    // then we delete it locally
    assertThat(fileAccess.wasDeleted(fooDotTxt), is(true));
  }

  @Test
  public void doNotEchoRemoteChange() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes remotely
    Update u = Update.newBuilder().setPath("foo.txt").setData(ByteString.copyFrom(data2)).build();
    changes.add(u);
    // when we notice and save that write locally
    l.poll();
    changes.add(Update.newBuilder(u).setLocal(true).build());
    // then we don't echo it back out to the remote
    l.poll();
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  @Test
  public void doNotEchoRemoteDelete() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it is deleted remotely
    Update u = Update.newBuilder().setPath("foo.txt").setDelete(true).build();
    changes.add(u);
    // when we notice and save that delete locally
    l.poll();
    changes.add(Update.newBuilder(u).setLocal(true).build());
    // then we don't echo it back out to the remote
    l.poll();
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  @Test
  public void sendLocalSymlinkToRemote() throws Exception {
    // given we create a new symlink locally
    fileAccess.createSymlink(fooDotTxt, Paths.get("bar"));
    // when we notice
    Update u = Update.newBuilder().setPath("foo.txt").setLocal(true).setSymlink("bar").build();
    changes.add(u);
    l.poll();
    // then we sent it to the remote
    assertThat(outgoing.values.size(), is(1));
    // and it has the correct data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getSymlink(), is("bar"));
    assertThat(sent.getData().isEmpty(), is(true));
    // and the time stamp
    assertThat(sent.getModTime(), is(1L));
    assertThat(sent.getLocal(), is(false));
  }

  @Test
  public void saveRemoteSymlink() throws Exception {
    // given a symlink is created remotely
    Update u = Update.newBuilder().setPath("foo.txt").setSymlink("bar").setModTime(10L).build();
    changes.add(u);
    // when we notice
    l.poll();
    // then we've saved it locally
    Path target = fileAccess.readSymlink(fooDotTxt);
    assertThat(target.toString(), is("bar"));
    assertThat(fileAccess.getModifiedTime(fooDotTxt), is(10L));
    // and when we notice the local event
    Update u2 = Update.newBuilder().setPath("foo.txt").setLocal(true).setSymlink("bar").setModTime(10L).build();
    changes.add(u2);
    l.poll();
    // then we don't echo it back to the remote
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  @Test
  public void handleFileBeingQueuedButThenDeleted() throws Exception {
    // given we detected a local file
    Update u = Update.newBuilder().setPath("foo.txt").setLocal(true).build();
    changes.add(u);
    // but it does not exist on disk anymore
    assertThat(fileAccess.exists(fooDotTxt), is(false));
    // when we notice
    l.poll();
    // then we handle it with no errors
    assertThat(outgoing.values.size(), is(0));
  }

  @Test
  public void handleFileSymlinkBeingQueuedButThenDeleted() throws Exception {
    // given we detected a local symlink
    Update u = Update.newBuilder().setPath("foo.txt").setSymlink("bar.txt").setLocal(true).build();
    changes.add(u);
    // but it does not exist on disk anymore
    assertThat(fileAccess.exists(fooDotTxt), is(false));
    // when we notice
    l.poll();
    // then we handle it with no errors
    assertThat(outgoing.values.size(), is(0));
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

package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.junit.Test;

import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;

public class SyncLogicTest {

  private static final Path fooDotTxt = Paths.get("foo.txt");
  private final static byte[] data = new byte[] { 1, 2, 3, 4 };
  private final static byte[] data2 = new byte[] { 1, 2, 3, 4, 5, 6 };
  private final Queues queues = new Queues();
  private final BlockingQueue<Update> changes = queues.incomingQueue;
  private final StubObserver<Update> outgoing = new StubObserver<>();
  private final StubFileAccess fileAccess = new StubFileAccess();
  private final UpdateTree tree = UpdateTree.newRoot();
  private final SyncLogic l = new SyncLogic(queues, fileAccess, tree);

  @Test
  public void sendLocalChangeToRemote() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes locally
    Update u = Update.newBuilder().setPath("foo.txt").setLocal(true).build();
    changes.add(u);
    // when we notice
    poll();
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
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // that also exists on the remote
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // and it is deleted locally
    changes.add(Update.newBuilder().setPath("foo.txt").setDelete(true).setLocal(true).build());
    fileAccess.delete(fooDotTxt);
    // when we notice
    poll();
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
  public void sendLocalDirectoryToRemote() throws Exception {
    // given a directory is created locally
    fileAccess.mkdir(Paths.get("foo"));
    // when we notice
    changes.add(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(1L).setLocal(true).build());
    poll();
    // then we sent it to the remote
    assertThat(outgoing.values.size(), is(1));
    // and we don't need to send any data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getData().size(), is(0));
    assertThat(sent.getModTime(), is(1L));
    assertThat(sent.getDirectory(), is(true));
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
    poll();
    // then we've saved it locally
    ByteString data = fileAccess.read(fooDotTxt);
    assertThat(data.toByteArray(), is(data2));
    assertThat(fileAccess.getModifiedTime(fooDotTxt), is(10L));
  }

  @Test
  public void saveRemoteDeleteLocally() throws Exception {
    // given we have an existing local file
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it is deleted remotely
    changes.add(Update.newBuilder().setPath("foo.txt").setDelete(true).setModTime(2L).build());
    // when we notice
    poll();
    // then we delete it locally
    assertThat(fileAccess.wasDeleted(fooDotTxt), is(true));
  }

  @Test
  public void saveRemoteDirectoryToLocally() throws Exception {
    // given a directory is created remotely
    changes.add(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(10L).build());
    // when we notice
    poll();
    // then we've creative it locally
    assertThat(fileAccess.isDirectory(Paths.get("foo")), is(true));
    assertThat(fileAccess.getModifiedTime(Paths.get("foo")), is(10L));
  }

  @Test
  public void doNotEchoRemoteChange() throws Exception {
    // given we have an existing local file
    tree.addLocal(Update.newBuilder().setPath("foo.txt").build());
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it changes remotely
    changes.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(ByteString.copyFrom(data2)).build());
    // when we notice and save that write locally
    poll();
    changes.add(Update.newBuilder().setPath("foo.txt").setLocal(true).build());
    // then we don't echo it back out to the remote
    poll();
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  @Test
  public void doNotEchoRemoteNewDirectoryWithNewChildren() throws Exception {
    // given the remote sends a new directory and a new file
    changes.add(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(1L).build());
    changes.add(Update.newBuilder().setPath("foo/bar.txt").setModTime(1L).setData(ByteString.copyFrom(data)).build());
    // when we notice and save the directory and file locally
    poll();
    poll();
    assertThat(fileAccess.exists(Paths.get("foo")), is(true));
    assertThat(fileAccess.exists(Paths.get("foo/bar.txt")), is(true));
    // however when we write a new file, the file system will inherently tick the mod time on its parent directory
    fileAccess.setModifiedTime(Paths.get("foo"), 2L);
    // and we notice the local changes
    changes.add(Update.newBuilder().setPath("foo").setDirectory(true).setLocal(true).build());
    changes.add(Update.newBuilder().setPath("foo/bar.txt").setLocal(true).build());
    // then we don't echo the foo directory back to the remote
    poll();
    poll();
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  @Test
  public void doNotEchoRemoteDelete() throws Exception {
    // given we have an existing local file
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // and it is deleted remotely
    changes.add(Update.newBuilder().setPath("foo.txt").setDelete(true).setModTime(2L).build());
    // when we notice and save that delete locally
    poll();
    assertThat(fileAccess.exists(fooDotTxt), is(false));
    changes.add(Update.newBuilder().setPath("foo.txt").setDelete(true).setLocal(true).build());
    // then we don't echo it back out to the remote
    poll();
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  @Test
  public void sendLocalSymlinkToRemote() throws Exception {
    // given we create a new symlink locally
    fileAccess.createSymlink(fooDotTxt, Paths.get("bar"));
    // when we notice
    Update u = Update.newBuilder().setPath("foo.txt").setLocal(true).setSymlink("bar").build();
    changes.add(u);
    poll();
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
    changes.add(Update.newBuilder().setPath("foo.txt").setSymlink("bar").setModTime(10L).build());
    // when we notice
    poll();
    // then we've saved it locally
    Path target = fileAccess.readSymlink(fooDotTxt);
    assertThat(target.toString(), is("bar"));
    assertThat(fileAccess.getModifiedTime(fooDotTxt), is(10L));
    // and when we notice the local event
    changes.add(Update.newBuilder().setPath("foo.txt").setLocal(true).setSymlink("bar").setModTime(10L).build());
    poll();
    // then we don't echo it back to the remote
    assertThat(outgoing.values.isEmpty(), is(true));
  }

  @Test
  public void skipFileBeingQueuedButThenDeleted() throws Exception {
    // given we detected a local file
    Update u = Update.newBuilder().setPath("foo.txt").setLocal(true).build();
    changes.add(u);
    // but it does not exist on disk anymore
    assertThat(fileAccess.exists(fooDotTxt), is(false));
    // when we notice
    poll();
    // then we handle it with no errors
    assertThat(outgoing.values.size(), is(0));
  }

  @Test
  public void skipSymlinkBeingQueuedButThenDeleted() throws Exception {
    // given we detected a local symlink
    changes.add(Update.newBuilder().setPath("foo.txt").setSymlink("bar.txt").setLocal(true).build());
    // but it does not exist on disk anymore
    assertThat(fileAccess.exists(fooDotTxt), is(false));
    // when we notice
    poll();
    // then we handle it with no errors
    assertThat(outgoing.values.size(), is(0));
  }

  @Test
  public void handleFilesGettingDeletedThenReCreated() throws Exception {
    // given we detect a local delete
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    changes.add(Update.newBuilder().setPath("foo.txt").setDelete(true).setLocal(true).build());
    // and it also exists on the remote
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    poll();
    assertThat(outgoing.values.size(), is(1));
    // and then file is re-created
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    fileAccess.setModifiedTime(fooDotTxt, 3L);
    changes.add(Update.newBuilder().setPath("foo.txt").setData(ByteString.copyFrom(data)).setLocal(true).build());
    poll();
    // then we issue both the delete+create
    assertThat(outgoing.values.size(), is(2));
  }

  @Test
  public void skipStaleLocalDelete() throws Exception {
    // given we have an existing local file
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data));
    // that also exists on the remote
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // and it is deleted locally
    Update u = Update.newBuilder().setPath("foo.txt").setDelete(true).setLocal(true).build();
    changes.add(u);
    // but our delete is stale (it's since been recreated)
    // fileAccess.delete(fooDotTxt);
    // when we notice
    poll();
    // then we don't send the delete to the remote
    assertThat(outgoing.values.size(), is(0));
  }

  @Test
  public void skipStaleLocalChange() throws Exception {
    // given we have an existing local file that is update
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data), 2L);
    changes.add(Update.newBuilder().setPath("foo.txt").setLocal(true).build());
    // and another update is also generated (e.g. from a touch/etc.)
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data2), 3L);
    changes.add(Update.newBuilder().setPath("foo.txt").setLocal(true).build());
    // when we notice
    poll();
    poll();
    // then we also send one update event to the remote
    assertThat(outgoing.values.size(), is(1));
    // and it has the correct data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getData().toByteArray(), is(data2));
    assertThat(sent.getModTime(), is(3L));
  }

  @Test
  public void skipStaleLocalSymlink() throws Exception {
    // given we create a new symlink locally
    fileAccess.createSymlink(fooDotTxt, Paths.get("bar"));
    fileAccess.setModifiedTime(fooDotTxt, 2L);
    changes.add(Update.newBuilder().setPath("foo.txt").setLocal(true).setSymlink("bar").build());
    // and then it's updated again
    fileAccess.createSymlink(fooDotTxt, Paths.get("bar2"));
    fileAccess.setModifiedTime(fooDotTxt, 3L);
    changes.add(Update.newBuilder().setPath("foo.txt").setLocal(true).setSymlink("bar2").build());
    // when we notice
    poll();
    poll();
    // then we sent it to the remote only once
    assertThat(outgoing.values.size(), is(1));
    // and it has the correct data
    Update sent = outgoing.values.get(0);
    assertThat(sent.getSymlink(), is("bar2"));
    assertThat(sent.getModTime(), is(3L));
  }

  @Test
  public void handleLocalOverflow() throws Exception {
    // given we have an existing file that is already in sync
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data), 1L);
    // and our file watcher overflows
    changes.add(Update.newBuilder().setPath("").setLocal(true).setData(UpdateTree.localOverflowMarker).build());
    poll();
    // then we assume (temporarily, while the recrawl happens) that all nodes are deleted
    assertThat(tree.find("foo.txt").getLocal().getDelete(), is(true));
    assertThat(tree.find("foo.txt").getLocal().getModTime(), is(1L));
    // and we do not issue any remote updates 
    assertThat(outgoing.values.size(), is(0));
    // when our watcher recovers, and noticed foo.txt has actually changed
    changes.add(Update.newBuilder().setPath("foo.txt").setLocal(true).build());
    fileAccess.write(fooDotTxt, ByteBuffer.wrap(data), 2L);
    changes.add(Update.newBuilder().setPath("").setLocal(true).setData(UpdateTree.overflowRecoveredMarker).build());
    poll();
    poll();
    // then we've unmarked foo for deletion
    assertThat(tree.find("foo.txt").getLocal().getDelete(), is(false));
    // and we've sent it to the remote
    assertThat(outgoing.values.size(), is(1));
    // and it has the correct data
    assertThat(outgoing.values.get(0).getPath(), is("foo.txt"));
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

  private void poll() throws Exception {
    l.poll();
    new SaveToLocal(queues, fileAccess).drain();
    new SaveToRemote(queues, fileAccess, outgoing).drain();
  }

}

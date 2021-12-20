package mirror;

import com.google.protobuf.ByteString;
import mirror.UpdateTree.Node;
import mirror.UpdateTreeDiff.DiffResults;
import org.jooq.lambda.Seq;
import org.junit.Test;

import static com.google.common.collect.Lists.newArrayList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.jooq.lambda.Seq.seq;

public class UpdateTreeDiffInboundOnlyTest {

  private static final ByteString data = ByteString.copyFrom(new byte[] { 1, 2, 3, 4 });
  private UpdateTree tree = UpdateTree.newRoot();
  private DiffResults results = null;
  private SyncDirection syncDirection = SyncDirection.INBOUND;

  @Test
  public void noSendLocalNewFileToRemote() {
    // given a local file that is new
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    // we don't send it
    diff();
    assertNoResults();
  }

  @Test
  public void noSendLocalChangedFileToRemote() {
    // given a local file that is newer
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    diff();
    // then we don't send the file to the remote
    assertNoResults();
  }

  @Test
  public void skipLocalMissingFileThatIsOnRemote() {
    // given a remote file that does not exist locally (and we don't have data for it yet)
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).setData(UpdateTree.initialSyncMarker).build());
    diff();
    // then we don't do anything
    assertNoResults();
    // and when we do have data, then we will save it
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).setData(data).build());
    diff();
    assertSaveLocally("foo.txt");
  }

  @Test
  public void skipLocalStaleFileThatIsOnRemote() {
    // given a remote file that is stale locally (and we don't have data for it yet)
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(UpdateTree.initialSyncMarker).build());
    diff();
    // then we don't do anything
    assertNoResults();
    // and when we do have data, then we will save it
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(data).build());
    diff();
    assertSaveLocally("foo.txt");
  }

  @Test
  public void noSendLocalNewSymlinkToRemote() {
    // given a local symlink that is new
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    diff();
    // then we don't send the file to the remote
    assertNoResults();
  }

  @Test
  public void noSendLocalChangedSymlinkToRemote() {
    // given a local symlink that is chagned
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar2").build());
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(1L).setSymlink("bar").build());
    diff();
    // then we don't send the file to the remote
    assertNoResults();
  }

  @Test
  public void noSendLocalNewDirectoryToRemote() {
    // given a local directory that is new
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    diff();
    // then we don't send the file to the remote
    assertNoResults();
  }

  @Test
  public void noSendLocalNewNestedFileToRemote() {
    // given a local file that is new
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/foo.txt").setModTime(2L).build());
    diff();
    // then we don't send the file to the remote
    assertNoResults();
  }

  @Test
  public void deleteLocalFileThatIsNowADirectory() {
    // given a local file
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is a newer directory on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).setDirectory(true).build());
    diff();
    // then we delete the file
    assertSaveLocally("foo", "foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    // and create the directory
    assertThat(results.saveLocally.get(1).getDirectory(), is(true));
  }

  @Test
  public void deleteLocalFileThatIsNowASymlink() {
    // given a local file
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is a newer symlink on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    diff();
    // then we delete the file
    assertSaveLocally("foo", "foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    assertThat(results.saveLocally.get(1).getSymlink(), is("bar"));
  }

  @Test
  public void leaveLocalFileThatWasADirectory() {
    // given a local file
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is an older directory on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    diff();
    // then we don't send the file to the remote + leave it alone locally
    assertNoResults();
    //
    assertNoSaveLocally();
  }

  @Test
  public void deleteLocalDirectoryThatIsNowAFile() {
    // given a local directory
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    // that is now a file on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).build());
    diff();
    // then we delete the directory to create the file
    assertSaveLocally("foo", "foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    assertThat(results.saveLocally.get(1).getDelete(), is(false));
  }

  @Test
  public void deleteLocalDirectoryThatIsNowASymlink() {
    // given a local directory
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar.txt").setModTime(2L).build());
    // that is now a symlink on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    diff();
    // then we delete the directory
    assertSaveLocally("foo", "foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    assertThat(results.saveLocally.get(1).getSymlink(), is("bar"));
    assertThat(tree.find("foo").getLocal().getSymlink(), is("bar"));
    assertThat(tree.find("foo/bar.txt").getLocal().getDelete(), is(true));
    // and when we diff again
    diff();
    // then we don't re-delete it
    assertNoResults();

    // client deletes foo/
    // server sends foo/
    // client sees Update(foo, local=true, delete=true, mod=) echo
    // --> should see already deleted, do nothing
    // client sees Update(foo, mod=X) from server

    // client deletes foo/
    // server sends foo
    // client sees Update(foo, mod=X) from server
    // client sees Update(foo, local=true, delete=true, mod=) echo
  }

  @Test
  public void deleteLocalDirectoryThatIsNowASymlinkDuringSync() {
    // given a local directory
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar.txt").setModTime(2L).build());
    // that is now a symlink on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    // instead of initialDiff
    diff();
    assertSaveLocally("foo", "foo");
    // then we delete the directory
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    // and also save the symlink
    assertThat(results.saveLocally.get(1).getSymlink(), is("bar"));
    // and when we diff again
    diff();
    // then we don't re-delete it
    assertNoResults();
  }

  @Test
  public void leavelLocalDirectoryThatWasAFile() {
    // given a local directory
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    // that is an older file file on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(1L).build());
    diff();
    // then we send our directory to the remote, and leave it alone locally
    assertNoResults();
  }

  @Test
  public void deleteLocalSymlinkThatIsNowAFile() {
    // given a local symlink
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is now a file on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).build());
    diff();
    // then we delete the symlink to create the file
    assertSaveLocally("foo", "foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    assertThat(results.saveLocally.get(1).getDelete(), is(false));
    // and when we diff again
    diff();
    // then we don't re-delete it
    assertNoResults();
  }

  @Test
  public void deleteLocalSymlinkThatIsNowADirectory() {
    // given a local symlink
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is now a directory on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).setDirectory(true).build());
    diff();
    // then we delete the symlink
    assertSaveLocally("foo", "foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    assertThat(results.saveLocally.get(1).getDirectory(), is(true));
  }

  @Test
  public void leaveLocalSymlinkThatWasAFile() {
    // given a local symlink
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is an older file on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(1L).build());
    diff();
    // then we don't send our symlink to the remote, and leave it alone locally
    assertNoResults();
  }

  @Test
  public void skipLocalFileIfParentDirectoryHasBeenRemoved() {
    // given a local file
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/foo.txt").setModTime(1L).setSymlink("bar").build());
    // but the directory is now a symlink on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    diff();
    // then we delete our local foo and don't send anything to the remote
    assertSaveLocally("foo", "foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    assertThat(results.saveLocally.get(1).getSymlink(), is("bar"));
    assertsendToRemote();
  }

  @Test
  public void skipLocalFileThatIsIgnored() {
    // given a local file
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // that is locally ignored
    tree.addLocal(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").build());
    diff();
    // then we don't sync the local foo.txt file, but we do sync .gitignore
    assertNoResults();
  }

  @Test
  public void skipLocalNewFileThatIsNowIgnored() {
    // given a local file
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // but the remote has a .gitignore in place
    tree.addRemote(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").build());
    diff();
    // then we don't sync the local file
    assertSaveLocally(".gitignore");
    assertsendToRemote();
  }

  @Test
  public void skipLocalFileInAnIgnoredDirectory() {
    // given a local file
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/foo.txt").setModTime(1L).build());
    tree.addLocal(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    // and the .gitignore exists remotely as well
    tree.addRemote(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    diff();
    // then we don't sync the local file
    assertNoResults();
  }

  @Test
  public void skipRemoteFileThatIsIgnored() {
    // given a remote file
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // that is remotely ignored
    tree.addRemote(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").setData(data).build());
    diff();
    // then we don't sync the local foo.txt file, but we do sync .gitignore
    assertSaveLocally(".gitignore");
  }

  @Test
  public void includeLocalFileInAnIgnoredDirectoryThatIsExplicitlyIncluded() {
    // given a local file
    PathRules e = new PathRules();
    PathRules i = new PathRules("*.txt");
    tree = UpdateTree.newRoot(new MirrorPaths(null, null, i, e, false, newArrayList()));
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/foo.txt").setModTime(1L).build());
    tree.addLocal(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    // and the .gitignore exists remotely as well
    tree.addRemote(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    diff();
    // then we do
    assertNoResults();
  }

  @Test
  public void saveNewRemoteFileLocally() {
    // given a remote file that is new
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(data).build());
    diff();
    // then we save the file to locally
    assertSaveLocally("foo.txt");
    // assertThat(nodeCapture.getValue().getUpdate().getData(), is(data));
    // and then clear the data from the tree afterwards
    Node foo = tree.getChildren().get(0);
    assertThat(foo.getName(), is("foo.txt"));
    assertThat(foo.getRemote().getData(), is(UpdateTree.initialSyncMarker));
    // and we don't resave it again on the next diff
    diff();
    assertNoResults();
  }

  @Test
  public void saveNewRemoteDirectoryLocally() {
    // given a remote directory that is new
    tree.addRemote(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    diff();
    // then we save the directory to locally
    assertSaveLocally("foo");
    // and we don't resave it again on the next diff
    diff();
    assertNoResults();
  }

  @Test
  public void saveNewRemoteDirectoryAndThenFileLocally() {
    // given a remote directory that is new
    tree.addRemote(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    // and it also has a file in it
    tree.addRemote(Update.newBuilder().setPath("foo/bar.txt").setData(data).setModTime(2L).build());
    diff();
    // then we save the directory to locally
    assertSaveLocally("foo", "foo/bar.txt");
    // and we don't resave it again on the next diff
    diff();
    assertNoResults();
  }

  @Test
  public void deleteWhenFileDeletedLocally() {
    // given a file that exists on both local and remote
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // and it is deleted locally
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());
    diff();
    // then we don't send the delete to the remote
    assertNoResults();
    // and we don't resend it again on the next diff
    diff();
    assertNoResults();
  }

  @Test
  public void deleteWhenFileDeletedRemote() {
    // given a file that exists on both local and remote
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // and it is deleted on the remote
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());
    diff();
    // then we delete it locally
    assertSaveLocally("foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));
    assertThat(results.saveLocally.get(0).getLocal(), is(false));
    // and we don't resend it again on the next diff
    diff();
    assertNoResults();
  }

  @Test
  public void recreateWhenFileDeletedAndCreatedLocally() {
    // given a file that exists on both local and remote
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // and it is deleted locally
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());
    diff();
    // then we don't send the delete to the remote
    assertNoResults();
    // when it's re-created locally
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(4L).build());
    diff();
    // then we don't send the delete to the remote
    assertNoResults();
  }

  @Test
  public void deleteMultipleLevelsLocally() {
    // given a tree of foo/bar/zaz.txt that exists on both local and remote
    tree.addLocal(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar").setDirectory(true).setModTime(2L).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar/zaz.txt").setModTime(2L).build());

    tree.addRemote(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo/bar").setDirectory(true).setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo/bar/zaz.txt").setModTime(2L).build());

    // and it is deleted locally (i verified the inotify events are fired child-first
    tree.addLocal(Update.newBuilder().setPath("foo/bar/zaz.txt").setModTime(3L).setDelete(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar").setModTime(3L).setDelete(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());

    // then we don't send anything
    diff();
    assertNoResults();

    // and we don't send it on the next diff
    diff();
    assertNoResults();
    assertThat(tree.find("foo").getChildren().size(), is(1));
    assertThat(tree.find("foo/bar").getChildren().size(), is(1));
  }

  @Test
  public void deleteMultipleLevelsRemotely() {
    // given a tree of foo/bar/zaz.txt that exists on both local and remote
    tree.addLocal(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar").setDirectory(true).setModTime(2L).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar/zaz.txt").setModTime(2L).build());

    tree.addRemote(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo/bar").setDirectory(true).setModTime(2L).build());
    tree.addRemote(Update.newBuilder().setPath("foo/bar/zaz.txt").setModTime(2L).build());

    // and it is deleted remotely (per last test, only a delete foo will come across)
    tree.addRemote(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());

    // then we only need to delete the local directory
    diff();
    assertSaveLocally("foo");
    assertThat(results.saveLocally.get(0).getDelete(), is(true));

    // and we preemptively consider the local children deleted
    assertThat(tree.find("foo/bar").getLocal().getDelete(), is(true));
    assertThat(tree.find("foo/bar/zaz.txt").getLocal().getDelete(), is(true));

    // and we don't resend it again on the next diff
    diff();
    assertNoResults();
    assertThat(tree.find("foo").getChildren().size(), is(1));
    assertThat(tree.find("foo/bar").getChildren().size(), is(1));

    // and when the deletes are echoed by the file system we don't resend the delete
    tree.addLocal(Update.newBuilder().setPath("foo/bar/zaz.txt").setDelete(true).build());
    tree.addLocal(Update.newBuilder().setPath("foo/bar").setDelete(true).build());
    // pretend we haven't seen the foo echo delete
    diff();
    assertNoResults();

    // now the foo echo comes by
    tree.addLocal(Update.newBuilder().setPath("foo").setDelete(true).build());
    diff();
    assertNoResults();
  }

//  @Test
//  public void clearDataOfStaleRemoteFile() {
//    // given a remote file that thought it was newer
//    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(data).build());
//    // and a local file that is actually newer
//    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(3L).build());
//    diff();
//    // then we ignore the remove change
//    assertNoSaveLocally();
//    // and clear it's data from the UpdateTree
//    Node foo = tree.getChildren().get(0);
//    assertThat(foo.getName(), is("foo.txt"));
//    assertThat(foo.getRemote().getData().size(), is(0));
//  }

  @Test
  public void clearDataErronouslySentRemoteFile() {
    // given a remote file that thought it was newer
    tree.addRemote(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(data).build());
    // and a local file that was already the same
    tree.addLocal(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    diff();
    // then we ignore the remove change
    assertNoSaveLocally();
    // and clear it's data from the UpdateTree
    Node foo = tree.getChildren().get(0);
    assertThat(foo.getName(), is("foo.txt"));
    assertThat(foo.getRemote().getData(), is(UpdateTree.initialSyncMarker));
  }

  private void diff() {
    results = new UpdateTreeDiff(tree, syncDirection).diff();
  }

  private void assertNoResults() {
    assertSaveLocally();
    assertSendToRemote();
  }

  private void assertNoSaveLocally() {
    assertThat(results.saveLocally.size(), is(0));
  }

  private void assertsendToRemote() {
    assertThat(results.sendToRemote.size(), is(0));
  }

  private void assertSendToRemote(String... paths) {
    assertThat(seq(results.sendToRemote).map(u -> u.getPath()).toList(), is(Seq.of(paths).toList()));
  }

  private void assertSaveLocally(String... paths) {
    assertThat(seq(results.saveLocally).map(u -> u.getPath()).toList(), is(Seq.of(paths).toList()));
  }

}

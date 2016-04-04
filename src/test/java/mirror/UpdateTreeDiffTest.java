package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.google.protobuf.ByteString;

import mirror.UpdateTree.Node;
import mirror.UpdateTreeDiff.DiffResults;

public class UpdateTreeDiffTest {

  private static final ByteString data = ByteString.copyFrom(new byte[] { 1, 2, 3, 4 });
  private UpdateTree local = UpdateTree.newRoot();
  private UpdateTree remote = UpdateTree.newRoot();
  private DiffResults results = Mockito.mock(DiffResults.class);
  private ArgumentCaptor<Update> nodeCapture = ArgumentCaptor.forClass(Update.class);

  @After
  public void after() {
    verifyNoMoreInteractions(results);
  }

  @Test
  public void sendLocalNewFileToRemote() {
    // given a local file that is new
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    diff();
    // then we send the file to the remote
    verify(results).sendToRemote(any());
    // and we don't resave it again on the next diff
    reset(results);
    diff();
  }

  @Test
  public void sendLocalChangedFileToRemote() {
    // given a local file that is newer
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    diff();
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void skipLocalMissingFileThatIsOnRemote() {
    // given a remote file that does not exist locally (and we don't have data for it yet)
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    diff();
    // then we don't do anything
    verifyNoMoreInteractions(results);
    // and when we do have data, then we will save it
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).setData(data).build());
    diff();
    verify(results).saveLocally(any());
  }

  @Test
  public void skipLocalStaleFileThatIsOnRemote() {
    // given a remote file that is stale locally (and we don't have data for it yet)
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    diff();
    // then we don't do anything
    verifyNoMoreInteractions(results);
    // and when we do have data, then we will save it
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(data).build());
    diff();
    verify(results).saveLocally(any());
  }

  @Test
  public void sendLocalNewSymlinkToRemote() {
    // given a local symlink that is new
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    diff();
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void sendLocalChangedSymlinkToRemote() {
    // given a local symlink that is chagned
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar2").build());
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).setSymlink("bar").build());
    diff();
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void sendLocalNewDirectoryToRemote() {
    // given a local directory that is new
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    diff();
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void sendLocalNewNestedFileToRemote() {
    // given a local file that is new
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/foo.txt").setModTime(2L).build());
    diff();
    // then we send the file to the remote
    verify(results, times(2)).sendToRemote(any());
  }

  @Test
  public void deleteLocalFileThatIsNowADirectory() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is a newer directory on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setDirectory(true).build());
    diff();
    // then we delete the file
    verify(results, times(2)).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getAllValues().get(0).getDelete(), is(true));
    // and create the directory
    assertThat(nodeCapture.getAllValues().get(1).getDirectory(), is(true));
  }

  @Test
  public void deleteLocalFileThatIsNowASymlink() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is a newer symlink on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    diff();
    // then we delete the file
    verify(results, times(2)).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getAllValues().get(0).getDelete(), is(true));
    assertThat(nodeCapture.getAllValues().get(1).getSymlink(), is("bar"));
  }

  @Test
  public void leaveLocalFileThatWasADirectory() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is an older directory on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    diff();
    // then we send our file to the remote, and leave it alone locally
    verify(results).sendToRemote(any());
    verifyNoMoreInteractions(results);
  }

  @Test
  public void deleteLocalDirectoryThatIsNowAFile() {
    // given a local directory
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    // that is now a file on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).build());
    diff();
    // then we delete the directory
    verify(results).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getDelete(), is(true));
  }

  @Test
  public void deleteLocalDirectoryThatIsNowASymlink() {
    // given a local directory
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/bar.txt").setModTime(2L).build());
    // that is now a symlink on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    diff();
    // then we delete the directory
    verify(results, times(2)).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getAllValues().get(0).getDelete(), is(true));
    assertThat(nodeCapture.getAllValues().get(1).getSymlink(), is("bar"));
    assertThat(local.getChildren().get(0).getUpdate().getSymlink(), is("bar"));
    assertThat(local.getChildren().get(0).getChildren().size(), is(0));
    // and when we diff again
    reset(results);
    diff();
    // then we don't re-delete it
    verifyNoMoreInteractions(results);
    
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
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/bar.txt").setModTime(2L).build());
    // that is now a symlink on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    // instead of initialDiff
    diff();
    verify(results, times(2)).saveLocally(nodeCapture.capture());
    // then we delete the directory
    assertThat(nodeCapture.getAllValues().get(0).getDelete(), is(true));
    // and also save the symlink
    assertThat(nodeCapture.getAllValues().get(1).getSymlink().isEmpty(), is(false));
    // and when we diff again
    reset(results);
    diff();
    // then we don't re-delete it
    verifyNoMoreInteractions(results);
  }

  @Test
  public void leavelLocalDirectoryThatWasAFile() {
    // given a local directory
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    // that is an older file file on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).build());
    diff();
    // then we send our directory to the remote, and leave it alone locally
    verify(results).sendToRemote(any());
    verifyNoMoreInteractions(results);
  }

  @Test
  public void deleteLocalSymlinkThatIsNowAFile() {
    // given a local symlink
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is now a file on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).build());
    diff();
    // then we delete the symlink
    verify(results).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getDelete(), is(true));
    // and when we diff again
    reset(results);
    diff();
    // then we don't re-delete it
    verifyNoMoreInteractions(results);
  }

  @Test
  public void deleteLocalSymlinkThatIsNowADirectory() {
    // given a local symlink
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is now a directory on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setDirectory(true).build());
    diff();
    // then we delete the symlink
    verify(results, times(2)).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getAllValues().get(0).getDelete(), is(true));
    assertThat(nodeCapture.getAllValues().get(1).getDirectory(), is(true));
  }

  @Test
  public void leaveLocalSymlinkThatWasAFile() {
    // given a local symlink
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is an older file on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).build());
    diff();
    // then we send our symlink to the remote, and leave it alone locally
    verify(results).sendToRemote(any());
    verifyNoMoreInteractions(results);
  }

  @Test
  public void skipLocalFileIfParentDirectoryHasBeenRemoved() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/foo.txt").setModTime(1L).setSymlink("bar").build());
    // but the directory is now a symlink on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    diff();
    // then we delete our local foo and don't send anything to the remote
    verify(results, times(2)).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getAllValues().get(0).getDelete(), is(true));
    assertThat(nodeCapture.getAllValues().get(1).getSymlink(), is("bar"));
    verifyNoMoreInteractions(results);
  }

  @Test
  public void skipLocalFileThatIsIgnored() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // that is locally ignored
    local.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").build());
    diff();
    // then we don't sync the local foo.txt file, but we do sync .gitignore
    verify(results, times(1)).sendToRemote(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getPath(), is(".gitignore"));
  }

  @Test
  public void skipLocalNewFileThatIsNowIgnored() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // but the remote has a .gitignore in place
    remote.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").build());
    diff();
    // then we don't sync the local file
    verifyNoMoreInteractions(results);
  }

  @Test
  public void skipLocalFileInAnIgnoredDirectory() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/foo.txt").setModTime(1L).build());
    local.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    // and the .gitignore exists remotely as well
    remote.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    diff();
    // then we don't sync the local file
    verifyNoMoreInteractions(results);
  }

  @Test
  public void skipRemoteFileThatIsIgnored() {
    // given a remote file
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // that is remotely ignored
    remote.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").setData(data).build());
    diff();
    // then we don't sync the local foo.txt file, but we do sync .gitignore
    verify(results, times(1)).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getPath(), is(".gitignore"));
  }

  @Test
  public void includeLocalFileInAnIgnoredDirectoryThatIsExplicitlyIncluded() {
    // given a local file
    local.extraIncludes.setRules("*.txt");
    local.add(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/foo.txt").setModTime(1L).build());
    local.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    // and the .gitignore exists remotely as well
    remote.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    diff();
    // then we do
    verify(results).sendToRemote(any());
  }

  @Test
  public void saveNewRemoteFileLocally() {
    // given a remote file that is new
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).setData(data).build());
    diff();
    // then we save the file to locally
    verify(results).saveLocally(nodeCapture.capture());
    // assertThat(nodeCapture.getValue().getUpdate().getData(), is(data));
    // and then clear the data from the tree afterwards
    Node foo = remote.getChildren().get(0);
    assertThat(foo.getName(), is("foo.txt"));
    assertThat(foo.getUpdate().getData().size(), is(0));
    // and we don't resave it again on the next diff
    reset(results);
    diff();
    verifyNoMoreInteractions(results);
  }

  @Test
  public void saveNewRemoteDirectoryLocally() {
    // given a remote directory that is new
    remote.add(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    diff();
    // then we save the directory to locally
    verify(results).saveLocally(any());
    // and we don't resave it again on the next diff
    reset(results);
    diff();
    verifyNoMoreInteractions(results);
  }

  @Test
  public void saveNewRemoteDirectoryAndThenFileLocally() {
    // given a remote directory that is new
    remote.add(Update.newBuilder().setPath("foo").setDirectory(true).setModTime(2L).build());
    // and it also has a file in it
    remote.add(Update.newBuilder().setPath("foo/bar.txt").setData(data).setModTime(2L).build());
    diff();
    // then we save the directory to locally
    verify(results, times(2)).saveLocally(any());
    // and we don't resave it again on the next diff
    reset(results);
    diff();
    verifyNoMoreInteractions(results);
  }

  @Test
  public void deleteWhenFileDeletedLocally() {
    // given a file that exists on both local and remote
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    remote.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // and it is deleted locally
    local.add(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());
    diff();
    // then we send the delete to the remote
    verify(results).sendToRemote(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getDelete(), is(true));
    assertThat(nodeCapture.getValue().getLocal(), is(false));
    // and we don't resend it again on the next diff
    reset(results);
    diff();
    verifyNoMoreInteractions(results);
  }

  @Test
  public void deleteWhenFileDeletedRemote() {
    // given a file that exists on both local and remote
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    remote.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // and it is deleted on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());
    diff();
    // then we delete it locally
    verify(results).saveLocally(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getDelete(), is(true));
    assertThat(nodeCapture.getValue().getLocal(), is(false));
    // and we don't resend it again on the next diff
    reset(results);
    diff();
    verifyNoMoreInteractions(results);
  }

  @Test
  public void recreateWhenFileDeletedAndCreatedLocally() {
    // given a file that exists on both local and remote
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    remote.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // and it is deleted locally
    local.add(Update.newBuilder().setPath("foo").setModTime(3L).setDelete(true).build());
    diff();
    // then we send the delete to the remote
    verify(results).sendToRemote(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getDelete(), is(true));
    assertThat(nodeCapture.getValue().getLocal(), is(false));
    // when it's re-created locally
    local.add(Update.newBuilder().setPath("foo").setModTime(4L).build());
    reset(results);
    diff();
    // then we send the delete to the remote
    verify(results).sendToRemote(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getDelete(), is(false));
    assertThat(nodeCapture.getValue().getLocal(), is(false));
    // and we don't resend it again on the next diff
    reset(results);
    diff();
    verifyNoMoreInteractions(results);
  }

  private void diff() {
    new UpdateTreeDiff(local, remote, results).diff();
  }

}

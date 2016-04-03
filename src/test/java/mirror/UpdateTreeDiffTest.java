package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import mirror.UpdateTree.Node;
import mirror.UpdateTreeDiff.TreeResults;

public class UpdateTreeDiffTest {

  private UpdateTree local = UpdateTree.newRoot();
  private UpdateTree remote = UpdateTree.newRoot();
  private TreeResults results = Mockito.mock(TreeResults.class);
  private ArgumentCaptor<Node> nodeCapture = ArgumentCaptor.forClass(Node.class);

  @After
  public void after() {
    verifyNoMoreInteractions(results);
  }

  @Test
  public void sendLocalNewFileToRemote() {
    // given a local file that is new
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void sendLocalChangedFileToRemote() {
    // given a local file that is newer
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(2L).build());
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void skipLocalMissingFileThatIsOnRemote() {
    // given a remote file that does not exist locally
    remote.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we don't do anything
    verifyNoMoreInteractions(results);
  }

  @Test
  public void sendLocalNewSymlinkToRemote() {
    // given a local symlink that is new
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void sendLocalChangedSymlinkToRemote() {
    // given a local symlink that is chagned
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar2").build());
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).setSymlink("bar").build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void sendLocalNewDirectoryToRemote() {
    // given a local directory that is new
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we send the file to the remote
    verify(results).sendToRemote(any());
  }

  @Test
  public void sendLocalNewNestedFileToRemote() {
    // given a local file that is new
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/foo.txt").setModTime(2L).build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we send the file to the remote
    verify(results, times(2)).sendToRemote(any());
  }

  @Test
  public void deleteLocalFileThatIsNowADirectory() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is a newer directory on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setDirectory(true).build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we delete the file
    verify(results).deleteLocally(any());
  }

  @Test
  public void deleteLocalFileThatIsNowASymlink() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is a newer symlink on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we delete the file
    verify(results).deleteLocally(any());
  }

  @Test
  public void leaveLocalFileThatWasADirectory() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).build());
    // that is an older directory on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    new UpdateTreeDiff(results).diff(local, remote);
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
    new UpdateTreeDiff(results).diff(local, remote);
    // then we delete the directory
    verify(results).deleteLocally(any());
  }

  @Test
  public void deleteLocalDirectoryThatIsNowASymlink() {
    // given a local directory
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    // that is now a symlink on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setSymlink("bar").build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we delete the directory
    verify(results).deleteLocally(any());
  }

  @Test
  public void leavelLocalDirectoryThatWasAFile() {
    // given a local directory
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setDirectory(true).build());
    // that is an older file file on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).build());
    new UpdateTreeDiff(results).diff(local, remote);
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
    new UpdateTreeDiff(results).diff(local, remote);
    // then we delete the symlink
    verify(results).deleteLocally(any());
  }

  @Test
  public void deleteLocalSymlinkThatIsNowADirectory() {
    // given a local symlink
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is now a directory on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(3L).setDirectory(true).build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we delete the symlink
    verify(results).deleteLocally(any());
  }

  @Test
  public void leaveLocalSymlinkThatWasAFile() {
    // given a local symlink
    local.add(Update.newBuilder().setPath("foo").setModTime(2L).setSymlink("bar").build());
    // that is an older file on the remote
    remote.add(Update.newBuilder().setPath("foo").setModTime(1L).build());
    new UpdateTreeDiff(results).diff(local, remote);
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
    new UpdateTreeDiff(results).diff(local, remote);
    // then we delete our local foo and don't send anything to the remote
    verify(results).deleteLocally(any());
    verifyNoMoreInteractions(results);
  }

  @Test
  public void skipLocalFileThatIsIgnored() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // that is locally ignored
    local.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we don't sync the local foo.txt file, but we do sync .gitignore
    verify(results, times(1)).sendToRemote(nodeCapture.capture());
    assertThat(nodeCapture.getValue().getName(), is(".gitignore"));
  }

  @Test
  public void skipLocalNewFileThatIsNowIgnored() {
    // given a local file
    local.add(Update.newBuilder().setPath("foo.txt").setModTime(1L).build());
    // but the remote has a .gitignore in place
    remote.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("*.txt").build());
    new UpdateTreeDiff(results).diff(local, remote);
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
    new UpdateTreeDiff(results).diff(local, remote);
    // then we don't sync the local file
    verifyNoMoreInteractions(results);
  }

  @Test
  public void includeLocalFileInAnIgnoredDirectoryThatIsExplicitlyIncluded() {
    // given a local file
    local.explicitIncludes.setRules("*.txt");
    local.add(Update.newBuilder().setPath("foo").setModTime(1L).setDirectory(true).build());
    local.add(Update.newBuilder().setPath("foo/foo.txt").setModTime(1L).build());
    local.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    // and the .gitignore exists remotely as well
    remote.add(Update.newBuilder().setPath(".gitignore").setModTime(1L).setIgnoreString("foo/").build());
    new UpdateTreeDiff(results).diff(local, remote);
    // then we do
    verify(results).sendToRemote(any());
  }

}

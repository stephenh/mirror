package mirror;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.google.protobuf.ByteString;

import mirror.UpdateTree.Node;

public class UpdateTreeTest {

  private final UpdateTree root = UpdateTree.newRoot();

  @Test
  public void addFileInRoot() {
    root.add(Update.newBuilder().setPath("foo.txt").build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getName(), is("foo.txt"));
  }

  @Test
  public void addDirectoryInRoot() {
    root.add(Update.newBuilder().setPath("foo").setDirectory(true).build());
    assertThat(root.getChildren().size(), is(1));
    assertThat(root.getChildren().get(0).getName(), is("foo"));
    assertThat(root.getChildren().get(0).isDirectory(), is(true));
  }

  @Test
  public void addFileInSubDirectory() {
    root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
    root.add(Update.newBuilder().setPath("bar/foo.txt").build());
    assertThat(root.getChildren().size(), is(1));
    Node bar = root.getChildren().get(0);
    assertThat(bar.getChildren().size(), is(1));
    assertThat(bar.getChildren().get(0).getName(), is("foo.txt"));
  }

  @Test
  public void addDirectoryInSubDirectory() {
    root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
    root.add(Update.newBuilder().setPath("bar/foo").setDirectory(true).build());
    assertThat(root.getChildren().size(), is(1));
    Node bar = root.getChildren().get(0);
    assertThat(bar.getChildren().size(), is(1));
    assertThat(bar.getChildren().get(0).getName(), is("foo"));
    assertThat(bar.getChildren().get(0).isDirectory(), is(true));
  }

  @Test
  public void failsIfDirectoryDoesNotExistYet() {
    try {
      root.add(Update.newBuilder().setPath("bar/foo").setDirectory(true).build());
      fail();
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage(), is("Directory bar not found for update bar/foo"));
    }
  }

  @Test
  public void failsIfDirectoryIsAlreadyAFile() {
    try {
      root.add(Update.newBuilder().setPath("bar").build());
      root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
      fail();
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage(), is("Adding directory bar already exists as a file"));
    }
  }

  @Test
  public void failsIfFileIsAlreadyADirectory() {
    try {
      root.add(Update.newBuilder().setPath("bar").setDirectory(true).build());
      root.add(Update.newBuilder().setPath("bar").build());
      fail();
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage(), is("Adding file bar already exists as a directory"));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void failsIfDataIsPresnt() {
    root.add(Update.newBuilder().setPath("foo.txt").setData(ByteString.copyFromUtf8("asdf")).build());
  }

  @Test(expected = IllegalArgumentException.class)
  public void failsIfPathStartsWithSlash() {
    root.add(Update.newBuilder().setPath("/foo").build());
  }

  @Test(expected = IllegalArgumentException.class)
  public void failsIfPathEndsWithSlash() {
    root.add(Update.newBuilder().setPath("foo/").build());
  }
}

package mirror;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class InitialState {

  private final Path root;
  private final FileAccess fs;

  public InitialState(Path root, FileAccess fs) {
    this.root = root;
    this.fs = fs;
  }

  public List<Update> prepare(List<Update> updates) throws IOException {
    List<Update> copy = new ArrayList<>(updates.size());
    for (Update update : updates) {
      Path path = root.resolve(update.getPath());
      long modTime = fs.getModifiedTime(path);
      copy.add(update.toBuilder().setModTime(modTime).build());
    }
    return copy;
  }

}

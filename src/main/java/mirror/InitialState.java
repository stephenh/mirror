package mirror;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class InitialState {

  private final FileAccess fs;

  public InitialState(FileAccess fs) {
    this.fs = fs;
  }

  public List<Update> prepare(List<Update> updates) throws IOException {
    List<Update> copy = new ArrayList<>(updates.size());
    for (Update update : updates) {
      long modTime = fs.getModifiedTime(Paths.get(update.getPath()));
      copy.add(update.toBuilder().setModTime(modTime).build());
    }
    return copy;
  }

}

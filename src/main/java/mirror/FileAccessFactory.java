package mirror;

import java.nio.file.Path;

public interface FileAccessFactory {

  FileAccess newFileAccess(Path absoluteRoot);

}

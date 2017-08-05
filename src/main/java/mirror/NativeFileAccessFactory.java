package mirror;

import java.nio.file.Path;

public class NativeFileAccessFactory implements FileAccessFactory {

  @Override
  public FileAccess newFileAccess(Path absoluteRoot) {
    return new NativeFileAccess(absoluteRoot);
  }

}

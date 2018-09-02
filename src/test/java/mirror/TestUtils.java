package mirror;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

public class TestUtils {

  public static String readFileToString(final File file) throws IOException {
    return FileUtils.readFileToString(file, UTF_8);
  }

  public static void writeStringToFile(final File file, final String data) throws IOException {
    FileUtils.writeStringToFile(file, data, UTF_8);
  }

}

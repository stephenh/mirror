package mirror;

import joist.util.Execute;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TestUtils {

  public static String readFileToString(final File file) throws IOException {
    return FileUtils.readFileToString(file, UTF_8);
  }

  public static void writeStringToFile(final File file, final String data) throws IOException {
    FileUtils.writeStringToFile(file, data, UTF_8);
  }

  public static void move(final String from, final String to) {
    new Execute(new String[] { "mv", from, to }).toSystemOut();
  }
}

package mirror;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.google.common.io.Files;

public class Digest {

  public static String getHash(Path path) throws IOException {
    try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
      MessageDigest md = MessageDigest.getInstance("MD5");
      ByteBuffer bbf = ByteBuffer.allocateDirect(8192);
      int b = fc.read(bbf);
      while ((b != -1) && (b != 0)) {
        bbf.flip();
        md.update(bbf);
        b = fc.read(bbf);
      }
      fc.close();
      return toHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  public static String getHash2(Path path) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      ByteBuffer bbf = Files.map(path.toFile());
      md.update(bbf); // still copies to a temp byte[] internally
      return toHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      sb.append(Integer.toHexString(0xFF & bytes[i]));
    }
    return sb.toString();
  }

}

package in.otrail.opay;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Loads the synthetic fixtures from modules/mod12/fixtures. */
final class Fixtures {
  private Fixtures() {}

  static File file(String name) {
    File[] candidates = {
        new File("../modules/mod12/fixtures", name),
        new File("modules/mod12/fixtures", name)
    };
    for (File candidate : candidates) if (candidate.isFile()) return candidate;
    throw new IllegalStateException("Fixture not found: " + name);
  }

  static byte[] bytes(String name) throws IOException {
    return Files.readAllBytes(file(name).toPath());
  }

  static String text(String name) throws IOException {
    return new String(bytes(name), java.nio.charset.StandardCharsets.UTF_8);
  }
}

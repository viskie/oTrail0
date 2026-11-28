package in.otrail.extensions.pop3;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Plain POP3 over TLS or TCP with UIDL listing when the server supports it. */
final class Pop3Client implements Closeable {
  static final class Entry {
    int number;
    String uid = "";
    long size;
  }

  private static final int TIMEOUT_MS = 20000;
  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;

  Pop3Client(Pop3Account account) throws IOException {
    if (account.host == null || account.host.trim().isEmpty())
      throw new IOException("No server host configured");
    Socket created;
    if (account.tls) {
      SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
      ssl.connect(new InetSocketAddress(account.host.trim(), account.port), TIMEOUT_MS);
      ssl.setSoTimeout(TIMEOUT_MS);
      ssl.startHandshake();
      created = ssl;
    } else {
      created = new Socket();
      created.connect(new InetSocketAddress(account.host.trim(), account.port), TIMEOUT_MS);
      created.setSoTimeout(TIMEOUT_MS);
    }
    socket = created;
    in = socket.getInputStream();
    out = socket.getOutputStream();
    String greeting = status();
    if (!ok(greeting)) {
      close();
      throw new IOException(greeting);
    }
    String userReply = command("USER " + account.user);
    if (!ok(userReply)) {
      close();
      throw new IOException(userReply);
    }
    String passReply = command("PASS " + account.password);
    if (!ok(passReply)) {
      close();
      throw new IOException(passReply);
    }
  }

  /** Full remote listing: UIDL identifiers when available, LIST sizes otherwise. */
  List<Entry> entries() throws IOException {
    List<Entry> list = new ArrayList<>();
    Map<Integer, Long> sizes = new HashMap<>();
    String listReply = command("LIST");
    if (ok(listReply)) {
      for (String line : multiline()) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 2) continue;
        try {
          sizes.put(Integer.parseInt(fields[0]), Long.parseLong(fields[1]));
        } catch (NumberFormatException ignored) {
          // Skip malformed listing rows.
        }
      }
    }
    String uidReply = command("UIDL");
    if (ok(uidReply)) {
      for (String line : multiline()) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 2) continue;
        try {
          Entry entry = new Entry();
          entry.number = Integer.parseInt(fields[0]);
          entry.uid = fields[1];
          Long size = sizes.get(entry.number);
          entry.size = size == null ? 0 : size;
          list.add(entry);
        } catch (NumberFormatException ignored) {
          // Skip malformed UIDL rows.
        }
      }
    } else {
      for (Map.Entry<Integer, Long> size : sizes.entrySet()) {
        Entry entry = new Entry();
        entry.number = size.getKey();
        entry.uid = "n" + entry.number + "-" + size.getValue();
        entry.size = size.getValue();
        list.add(entry);
      }
    }
    list.sort((a, b) -> Integer.compare(a.number, b.number));
    return list;
  }

  byte[] retrieve(int number) throws IOException {
    String reply = command("RETR " + number);
    if (!ok(reply)) throw new IOException(reply);
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    while (true) {
      byte[] line = readLine();
      if (line.length == 1 && line[0] == '.') break;
      if (line.length >= 2 && line[0] == '.' && line[1] == '.') {
        raw.write(line, 1, line.length - 1);
      } else {
        raw.write(line);
      }
      raw.write('\r');
      raw.write('\n');
    }
    return raw.toByteArray();
  }

  void delete(int number) throws IOException {
    String reply = command("DELE " + number);
    if (!ok(reply)) throw new IOException(reply);
  }

  private String command(String value) throws IOException {
    out.write((value + "\r\n").getBytes("US-ASCII"));
    out.flush();
    return status();
  }

  private String status() throws IOException {
    byte[] line = readLine();
    return new String(line, "ISO-8859-1");
  }

  private List<String> multiline() throws IOException {
    List<String> lines = new ArrayList<>();
    while (true) {
      byte[] line = readLine();
      if (line.length == 1 && line[0] == '.') break;
      String value = new String(line, "ISO-8859-1");
      lines.add(value.startsWith("..") ? value.substring(1) : value);
    }
    return lines;
  }

  private byte[] readLine() throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') break;
      if (b != '\r') line.write(b);
    }
    if (b == -1 && line.size() == 0) throw new IOException("Server closed the connection");
    return line.toByteArray();
  }

  private static boolean ok(String reply) {
    return reply != null && reply.toUpperCase(Locale.US).startsWith("+OK");
  }

  @Override public void close() {
    try {
      if (socket != null && !socket.isClosed()) {
        try {
          out.write("QUIT\r\n".getBytes("US-ASCII"));
          out.flush();
        } catch (IOException ignored) {
          // The session may already be gone.
        }
        socket.close();
      }
    } catch (IOException ignored) {
      // Nothing else to release.
    }
  }
}

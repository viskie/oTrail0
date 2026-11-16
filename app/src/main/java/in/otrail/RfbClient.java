package in.otrail;

import android.graphics.Bitmap;
import android.view.KeyEvent;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal RFB 3.8 client for a localhost-only, ADB-tunneled x11vnc session. */
final class RfbClient implements Closeable {
  interface Listener {
    void onConnected(Bitmap framebuffer);
    void onResized(Bitmap framebuffer);
    void onFrame();
    void onDisconnected(String message);
  }

  private static final int MAX_FRAMEBUFFER_PIXELS = 20_000_000;
  private final Listener listener;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Socket socket;
  private DataInputStream input;
  private volatile DataOutputStream output;
  private Bitmap framebuffer;
  private int width, height, bitsPerPixel, redMax, greenMax, blueMax,
      redShift, greenShift, blueShift;
  private boolean bigEndian;

  RfbClient(Listener listener) {
    this.listener = listener;
  }

  void connectAndRead() {
    String message = "Connection closed";
    try {
      connect();
      readMessages();
    } catch (Exception error) {
      message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    } finally {
      closeSocket();
      if (!closed.get()) listener.onDisconnected(message);
    }
  }

  private void connect() throws IOException {
    if (closed.get()) throw new IOException("Connection cancelled.");
    socket = new Socket();
    if (closed.get()) {
      socket.close();
      throw new IOException("Connection cancelled.");
    }
    socket.connect(new InetSocketAddress("127.0.0.1", 5900), 5000);
    socket.setTcpNoDelay(true);
    socket.setKeepAlive(true);
    input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

    byte[] version = new byte[12];
    input.readFully(version);
    if (!new String(version, StandardCharsets.US_ASCII).startsWith("RFB 003.")) {
      throw new IOException("Host did not offer an RFB/VNC server.");
    }
    output.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
    output.flush();
    int securityCount = input.readUnsignedByte();
    if (securityCount == 0) {
      throw new IOException(readReason());
    }
    int selectedSecurity = 0;
    for (int i = 0; i < securityCount; i++) {
      if (input.readUnsignedByte() == 1) selectedSecurity = 1;
    }
    if (selectedSecurity == 0) {
      throw new IOException("Host must use the authorized localhost-only ADB tunnel.");
    }
    output.writeByte(selectedSecurity);
    output.flush();
    if (input.readInt() != 0) throw new IOException(readReason());

    output.writeByte(1);
    output.flush();
    width = input.readUnsignedShort();
    height = input.readUnsignedShort();
    bitsPerPixel = input.readUnsignedByte();
    input.readUnsignedByte();
    bigEndian = input.readUnsignedByte() != 0;
    boolean trueColor = input.readUnsignedByte() != 0;
    redMax = input.readUnsignedShort();
    greenMax = input.readUnsignedShort();
    blueMax = input.readUnsignedShort();
    redShift = input.readUnsignedByte();
    greenShift = input.readUnsignedByte();
    blueShift = input.readUnsignedByte();
    skipFully(3);
    long nameLength = Integer.toUnsignedLong(input.readInt());
    if (nameLength > 16_384) throw new IOException("VNC desktop name is unexpectedly long.");
    skipFully(nameLength);
    if (!trueColor || (bitsPerPixel != 8 && bitsPerPixel != 16 && bitsPerPixel != 32)
        || redMax == 0 || greenMax == 0 || blueMax == 0) {
      throw new IOException("Host uses an unsupported VNC pixel format.");
    }
    framebuffer = createFramebuffer(width, height);

    output.writeByte(2);
    output.writeByte(0);
    output.writeShort(2);
    output.writeInt(0);
    output.writeInt(-223);
    output.flush();
    listener.onConnected(framebuffer);
    requestFramebuffer(false);
  }

  private String readReason() throws IOException {
    long length = Integer.toUnsignedLong(input.readInt());
    if (length > 4096) throw new IOException("VNC handshake failed.");
    byte[] reason = new byte[(int) length];
    input.readFully(reason);
    return new String(reason, StandardCharsets.UTF_8);
  }

  private void readMessages() throws IOException {
    while (!closed.get()) {
      int messageType = input.readUnsignedByte();
      if (messageType == 0) {
        input.readUnsignedByte();
        int rectangles = input.readUnsignedShort();
        for (int i = 0; i < rectangles; i++) readRectangle();
        listener.onFrame();
        requestFramebuffer(true);
      } else if (messageType == 2) {
        // Bell; there is no corresponding user-facing sound in this viewer.
      } else if (messageType == 3) {
        skipFully(3);
        long length = Integer.toUnsignedLong(input.readInt());
        if (length > 1_000_000) throw new IOException("VNC clipboard message is too large.");
        skipFully(length);
      } else if (messageType == 1) {
        throw new IOException("Host sent a color map for a true-color framebuffer.");
      } else {
        throw new IOException("Host sent an unsupported VNC message.");
      }
    }
  }

  private void skipFully(long count) throws IOException {
    while (count > 0) {
      long skipped = input.skip(count);
      if (skipped == 0) {
        if (input.read() == -1) throw new IOException("Unexpected end of VNC stream.");
        skipped = 1;
      }
      count -= skipped;
    }
  }

  private void readRectangle() throws IOException {
    int x = input.readUnsignedShort(), y = input.readUnsignedShort();
    int rectWidth = input.readUnsignedShort(), rectHeight = input.readUnsignedShort();
    int encoding = input.readInt();
    if (encoding == -223) {
      framebuffer = createFramebuffer(rectWidth, rectHeight);
      width = rectWidth;
      height = rectHeight;
      listener.onResized(framebuffer);
      return;
    }
    if (encoding != 0) throw new IOException("Host sent a non-raw VNC rectangle.");
    if (x + rectWidth > width || y + rectHeight > height) {
      throw new IOException("Host sent a framebuffer rectangle outside its bounds.");
    }
    int bytesPerPixel = bitsPerPixel / 8;
    byte[] row = new byte[Math.multiplyExact(rectWidth, bytesPerPixel)];
    int[] colors = new int[rectWidth];
    synchronized (framebuffer) {
      for (int rowIndex = 0; rowIndex < rectHeight; rowIndex++) {
        input.readFully(row);
        for (int column = 0; column < rectWidth; column++) {
          int pixel = 0;
          int offset = column * bytesPerPixel;
          if (bigEndian) {
            for (int byteIndex = 0; byteIndex < bytesPerPixel; byteIndex++)
              pixel = (pixel << 8) | (row[offset + byteIndex] & 0xff);
          } else {
            for (int byteIndex = bytesPerPixel - 1; byteIndex >= 0; byteIndex--)
              pixel = (pixel << 8) | (row[offset + byteIndex] & 0xff);
          }
          colors[column] = decodeColor(pixel, redMax, greenMax, blueMax,
              redShift, greenShift, blueShift);
        }
        framebuffer.setPixels(colors, 0, rectWidth, x, y + rowIndex, rectWidth, 1);
      }
    }
  }

  static int decodeColor(
      int pixel, int redMax, int greenMax, int blueMax, int redShift, int greenShift, int blueShift) {
    int red = (((pixel >>> redShift) & redMax) * 255 + redMax / 2) / redMax;
    int green = (((pixel >>> greenShift) & greenMax) * 255 + greenMax / 2) / greenMax;
    int blue = (((pixel >>> blueShift) & blueMax) * 255 + blueMax / 2) / blueMax;
    return 0xff000000 | red << 16 | green << 8 | blue;
  }

  private static Bitmap createFramebuffer(int width, int height) throws IOException {
    long pixels = (long) width * height;
    if (width <= 0 || height <= 0 || pixels > MAX_FRAMEBUFFER_PIXELS) {
      throw new IOException("Host framebuffer size is invalid or too large.");
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
  }

  private void requestFramebuffer(boolean incremental) throws IOException {
    synchronized (output) {
      output.write(3);
      output.write(incremental ? 1 : 0);
      output.write(0);
      output.write(0);
      output.write(0);
      output.write(0);
      output.write((width >>> 8) & 0xff);
      output.write(width & 0xff);
      output.write((height >>> 8) & 0xff);
      output.write(height & 0xff);
      output.flush();
    }
  }

  void sendPointer(int buttons, int x, int y) {
    DataOutputStream stream = output;
    if (stream == null || closed.get()) return;
    try {
      synchronized (stream) {
        stream.write(5);
        stream.write(buttons & 0xff);
        stream.write((x >>> 8) & 0xff);
        stream.write(x & 0xff);
        stream.write((y >>> 8) & 0xff);
        stream.write(y & 0xff);
        stream.flush();
      }
    } catch (IOException error) {
      closeSocket();
    }
  }

  void sendKey(int keysym, boolean down) {
    if (keysym == 0) return;
    DataOutputStream stream = output;
    if (stream == null || closed.get()) return;
    try {
      synchronized (stream) {
        stream.write(4);
        stream.write(down ? 1 : 0);
        stream.write(0);
        stream.write(0);
        stream.write(0);
        stream.write((keysym >>> 24) & 0xff);
        stream.write((keysym >>> 16) & 0xff);
        stream.write((keysym >>> 8) & 0xff);
        stream.write(keysym & 0xff);
        stream.flush();
      }
    } catch (IOException error) {
      closeSocket();
    }
  }

  static int keysymForKey(KeyEvent event) {
    if (event.getKeyCode() >= KeyEvent.KEYCODE_F1 && event.getKeyCode() <= KeyEvent.KEYCODE_F12)
      return 0xffbe + event.getKeyCode() - KeyEvent.KEYCODE_F1;
    switch (event.getKeyCode()) {
      case KeyEvent.KEYCODE_DEL: return 0xff08;
      case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
      case KeyEvent.KEYCODE_TAB: return 0xff09;
      case KeyEvent.KEYCODE_ENTER: return 0xff0d;
      case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
      case KeyEvent.KEYCODE_HOME: return 0xff50;
      case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
      case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
      case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
      case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
      case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
      case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
      case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
      case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
      case KeyEvent.KEYCODE_SHIFT_LEFT:
      case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xffe1;
      case KeyEvent.KEYCODE_CTRL_LEFT:
      case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xffe3;
      case KeyEvent.KEYCODE_ALT_LEFT:
      case KeyEvent.KEYCODE_ALT_RIGHT: return 0xffe9;
      case KeyEvent.KEYCODE_META_LEFT:
      case KeyEvent.KEYCODE_META_RIGHT: return 0xffeb;
      default:
        int unicode = event.getUnicodeChar();
        return unicode > 0 && unicode <= 0x10ffff
            ? unicode <= 0xff ? unicode : 0x01000000 | unicode
            : 0;
    }
  }

  void sendText(String text) {
    for (int offset = 0; offset < text.length();) {
      int codePoint = text.codePointAt(offset);
      offset += Character.charCount(codePoint);
      int keysym = codePoint <= 0xff ? codePoint : 0x01000000 | codePoint;
      sendKey(keysym, true);
      sendKey(keysym, false);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) closeSocket();
  }

  private void closeSocket() {
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Socket closure is best-effort while exiting the reader loop.
      }
    }
  }
}

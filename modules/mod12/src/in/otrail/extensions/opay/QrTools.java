package in.otrail.extensions.opay;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** QR rendering and decoding using the vendored ZXing core (Apache-2.0) codec. */
public final class QrTools {
  private QrTools() {}

  public static Bitmap encode(String text, int sizePx) throws WriterException {
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
    hints.put(EncodeHintType.MARGIN, 1);
    com.google.zxing.common.BitMatrix matrix =
        new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
    int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
    for (int y = 0; y < matrix.getHeight(); y++) {
      int offset = y * matrix.getWidth();
      for (int x = 0; x < matrix.getWidth(); x++)
        pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
    }
    Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
    bitmap.setPixels(pixels, 0, matrix.getWidth(), 0, 0, matrix.getWidth(), matrix.getHeight());
    return bitmap;
  }

  public static String decode(Bitmap bitmap) {
    if (bitmap == null) return null;
    int width = bitmap.getWidth(), height = bitmap.getHeight();
    int[] pixels = new int[width * height];
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
    RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
    return decodeSource(source);
  }

  public static String decodeYuv(byte[] luminance, int width, int height) {
    PlanarYUVLuminanceSource source =
        new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
    return decodeSource(source);
  }

  private static String decodeSource(com.google.zxing.LuminanceSource source) {
    MultiFormatReader reader = new MultiFormatReader();
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    reader.setHints(hints);
    try {
      Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
      return result == null ? null : result.getText();
    } catch (NotFoundException | ArrayIndexOutOfBoundsException | IllegalArgumentException ignored) {
      return null;
    } finally {
      reader.reset();
    }
  }

  /** Extracts the Y plane of a YUV_420_888 image without copying chroma. */
  public static byte[] luminance(android.media.Image image, int[] size) {
    android.media.Image.Plane[] planes = image.getPlanes();
    if (planes.length == 0) return null;
    android.media.Image.Plane y = planes[0];
    java.nio.ByteBuffer buffer = y.getBuffer();
    int width = image.getWidth(), height = image.getHeight();
    int rowStride = y.getRowStride(), pixelStride = y.getPixelStride();
    byte[] out = new byte[width * height];
    int position = buffer.position();
    if (pixelStride == 1 && rowStride == width) {
      buffer.get(out, 0, Math.min(out.length, buffer.remaining()));
    } else {
      for (int row = 0; row < height; row++) {
        int start = position + row * rowStride;
        for (int column = 0; column < width; column++) {
          int index = start + column * pixelStride;
          out[row * width + column] = index < buffer.limit() ? buffer.get(index) : 0;
        }
      }
    }
    if (size != null) { size[0] = width; size[1] = height; }
    return out;
  }
}

package in.otrail.extensions.opay;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Live Camera2 preview that decodes UPI QR codes. No vision service or network
 * library is used; frames stay in the process and are decoded by ZXing.
 */
public final class QrScannerView extends FrameLayout implements TextureView.SurfaceTextureListener {
  public interface Listener {
    void onQr(String text);
    void onError(String message);
    void onStarted();
  }

  private final TextureView preview;
  private final TextView hint;
  private HandlerThread thread;
  private Handler handler;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader reader;
  private Listener listener;
  private volatile boolean decoding;
  private long lastDecode;
  private boolean started;

  public QrScannerView(Context context) {
    super(context);
    setBackgroundColor(0xff000000);
    preview = new TextureView(context);
    addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    hint = new TextView(context);
    hint.setText("Point the camera at a UPI QR code");
    hint.setTextColor(0xff00d4ff);
    hint.setTextSize(12);
    hint.setGravity(Gravity.CENTER);
    hint.setBackgroundColor(0x99000000);
    LayoutParams hintParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    hintParams.gravity = Gravity.BOTTOM;
    addView(hint, hintParams);
    preview.setSurfaceTextureListener(this);
  }

  public void setListener(Listener listener) { this.listener = listener; }

  public void start() {
    if (started) return;
    started = true;
    thread = new HandlerThread("opay-qr");
    thread.start();
    handler = new Handler(thread.getLooper());
    if (preview.isAvailable()) open(preview.getSurfaceTexture());
  }

  public void stop() {
    started = false;
    closeCamera();
    if (thread != null) {
      thread.quitSafely();
      try { thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
      thread = null;
    }
    handler = null;
  }

  @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
    if (started) open(surface);
  }

  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }

  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
    closeCamera();
    return true;
  }

  @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

  private void open(SurfaceTexture texture) {
    if (texture == null || handler == null) return;
    try {
      CameraManager manager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
      String id = null;
      for (String candidate : manager.getCameraIdList()) {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(candidate);
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
          id = candidate;
          break;
        }
        if (id == null) id = candidate;
      }
      if (id == null) {
        fail("No camera is available on this device.");
        return;
      }
      CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
      StreamConfigurationMap map = characteristics.get(
          CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      Size largest = map == null ? null : best(map.getOutputSizes(ImageReader.class));
      if (largest == null) {
        fail("The camera does not expose a preview stream.");
        return;
      }
      preview.getSurfaceTexture().setDefaultBufferSize(largest.getWidth(), largest.getHeight());
      reader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
          android.graphics.ImageFormat.YUV_420_888, 2);
      reader.setOnImageAvailableListener(this::frame, handler);
      manager.openCamera(id, new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
          camera = device;
          createSession(texture);
        }

        @Override public void onDisconnected(CameraDevice device) {
          device.close();
          camera = null;
        }

        @Override public void onError(CameraDevice device, int error) {
          device.close();
          camera = null;
          fail("Camera error " + error + ". Close other camera apps and retry.");
        }
      }, handler);
    } catch (CameraAccessException | SecurityException | IllegalArgumentException error) {
      fail("Camera could not start: " + error.getMessage());
    }
  }

  private void createSession(SurfaceTexture texture) {
    try {
      texture.setDefaultBufferSize(reader.getWidth(), reader.getHeight());
      android.view.Surface surface = new android.view.Surface(texture);
      final CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      request.addTarget(surface);
      request.addTarget(reader.getSurface());
      request.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
      List<android.view.Surface> targets = new ArrayList<>();
      targets.add(surface);
      targets.add(reader.getSurface());
      camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession configured) {
          session = configured;
          try {
            configured.setRepeatingRequest(request.build(), null, handler);
            if (listener != null) listener.onStarted();
          } catch (CameraAccessException | IllegalStateException error) {
            fail("Preview could not start: " + error.getMessage());
          }
        }

        @Override public void onConfigureFailed(CameraCaptureSession failed) {
          fail("The camera rejected the preview configuration.");
        }
      }, handler);
    } catch (CameraAccessException | IllegalStateException error) {
      fail("Preview could not start: " + error.getMessage());
    }
  }

  private void frame(ImageReader source) {
    android.media.Image image = null;
    try {
      image = source.acquireLatestImage();
      if (image == null || decoding || camera == null) return;
      long now = System.currentTimeMillis();
      if (now - lastDecode < 300) return;
      lastDecode = now;
      decoding = true;
      int[] size = new int[2];
      byte[] luminance = QrTools.luminance(image, size);
      if (luminance == null) return;
      String text = QrTools.decodeYuv(luminance, size[0], size[1]);
      if (text != null && listener != null) {
        final String value = text;
        post(() -> listener.onQr(value));
      }
    } catch (IllegalStateException | IllegalArgumentException error) {
      fail("Camera frame error: " + error.getMessage());
    } finally {
      if (image != null) image.close();
      decoding = false;
    }
  }

  private void closeCamera() {
    try { if (session != null) session.close(); } catch (RuntimeException ignored) { }
    session = null;
    try { if (camera != null) camera.close(); } catch (RuntimeException ignored) { }
    camera = null;
    try { if (reader != null) reader.close(); } catch (RuntimeException ignored) { }
    reader = null;
  }

  private void fail(String message) {
    closeCamera();
    if (listener != null) post(() -> listener.onError(message));
  }

  private static Size best(Size[] sizes) {
    if (sizes == null || sizes.length == 0) return null;
    List<Size> sorted = new ArrayList<>(Arrays.asList(sizes));
    sorted.sort((left, right) -> Long.compare((long) right.getWidth() * right.getHeight(),
        (long) left.getWidth() * left.getHeight()));
    for (Size size : sorted)
      if (size.getWidth() <= 1280 && size.getHeight() <= 1280
          && size.getWidth() >= 480 && size.getHeight() >= 480) return size;
    return sorted.get(0);
  }
}

package in.otrail;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Collapsible VNC viewer for the localhost endpoint exposed by ADB reverse. */
public final class RemoteDesktopActivity extends Activity {
  private final ExecutorService connector = Executors.newSingleThreadExecutor();
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private VncCanvasView canvas;
  private LinearLayout controls;
  private TextView status;
  private EditText textInput;
  private volatile RfbClient client;
  private boolean connecting;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    canvas = new VncCanvasView();
    root.addView(canvas, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(8), dp(6), dp(8), dp(6));
    panel.setBackground(NeonBorderDrawable.panel(panel,
        NeonBorderDrawable.Palette.CURRENT, UiConfig.DIALOG_CORNER_RADIUS_DP));
    FrameLayout.LayoutParams panelPosition =
        new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    panelPosition.setMargins(dp(8), dp(8), dp(8), 0);
    root.addView(panel, panelPosition);

    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("Remote desktop · USB ADB", UiConfig.HEADING_SIZE_SP);
    title.setTextColor(UiConfig.HEADING);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Button collapse = button("Collapse", () -> {
      controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
      collapseText((Button) header.getChildAt(1));
    });
    header.addView(collapse);
    panel.addView(header);

    controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.VERTICAL);
    controls.setPadding(0, dp(4), 0, 0);
    panel.addView(controls);
    status = label("Start `otrail-screen` on Debian, then connect.", UiConfig.CAPTION_SIZE_SP);
    status.setTextColor(UiConfig.NAV_TEXT_COLOR);
    controls.addView(status);
    LinearLayout actions = new LinearLayout(this);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.addView(button("Connect", this::connect), new LinearLayout.LayoutParams(0, dp(44), 1));
    actions.addView(button("Disconnect", this::disconnect),
        new LinearLayout.LayoutParams(0, dp(44), 1));
    controls.addView(actions);
    textInput = new EditText(this);
    textInput.setSingleLine(true);
    textInput.setTextSize(UiConfig.BODY_SIZE_SP);
    textInput.setHint("Type text to send to the laptop");
    textInput.setTextColor(Color.WHITE);
    textInput.setHintTextColor(UiConfig.MUTED);
    controls.addView(textInput);
    controls.addView(button("Send text", this::sendText));
    setContentView(root);
  }

  private void connect() {
    if (connecting || client != null) return;
    connecting = true;
    status.setText("Connecting to 127.0.0.1:5900 through ADB reverse…");
    AtomicReference<RfbClient> candidateRef = new AtomicReference<>();
    RfbClient candidate = new RfbClient(new RfbClient.Listener() {
      @Override public void onConnected(Bitmap bitmap) {
        main.post(() -> {
          if (client != candidateRef.get()) return;
          canvas.setFramebuffer(bitmap);
          canvas.setRfbClient(candidateRef.get());
          canvas.requestFocus();
          connecting = false;
          status.setText("Connected · tap/drag to control · keyboard events are forwarded.");
        });
      }
      @Override public void onResized(Bitmap bitmap) {
        main.post(() -> {
          if (client == candidateRef.get()) canvas.setFramebuffer(bitmap);
        });
      }
      @Override public void onFrame() {
        main.post(() -> {
          if (client == candidateRef.get()) canvas.invalidate();
        });
      }
      @Override public void onDisconnected(String message) {
        main.post(() -> {
          if (client != candidateRef.get()) return;
          client = null;
          connecting = false;
          canvas.setRfbClient(null);
          status.setText("Disconnected: " + message);
        });
      }
    });
    candidateRef.set(candidate);
    client = candidate;
    connector.execute(candidate::connectAndRead);
  }

  private void disconnect() {
    RfbClient current = client;
    client = null;
    connecting = false;
    if (current != null) current.close();
    canvas.setRfbClient(null);
    status.setText("Disconnected.");
  }

  private void sendText() {
    RfbClient current = client;
    if (current == null) {
      status.setText("Connect before sending keyboard input.");
      return;
    }
    current.sendText(textInput.getText().toString());
    textInput.setText("");
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (textInput != null && textInput.hasFocus()) return super.dispatchKeyEvent(event);
    RfbClient current = client;
    int keysym = RfbClient.keysymForKey(event);
    if (current != null && keysym != 0) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) current.sendKey(keysym, true);
      else if (event.getAction() == KeyEvent.ACTION_UP) current.sendKey(keysym, false);
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  protected void onDestroy() {
    disconnect();
    connector.shutdownNow();
    super.onDestroy();
  }

  private void collapseText(Button button) {
    button.setText(controls.getVisibility() == View.VISIBLE ? "Collapse" : "Expand");
  }

  private TextView label(String value, int sizeSp) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextSize(sizeSp);
    text.setTypeface(android.graphics.Typeface.create(
        UiConfig.BODY_FONT, UiConfig.BODY_FONT_STYLE));
    return text;
  }

  private Button button(String value, Runnable action) {
    Button button = new Button(this);
    button.setText(value);
    button.setAllCaps(false);
    button.setTextSize(UiConfig.BUTTON_SIZE_SP);
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private final class VncCanvasView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap framebuffer;
    private RfbClient rfbClient;
    private int pointerButtons;

    VncCanvasView() {
      super(RemoteDesktopActivity.this);
      setFocusable(true);
      setFocusableInTouchMode(true);
    }

    void setFramebuffer(Bitmap value) {
      framebuffer = value;
      invalidate();
    }

    void setRfbClient(RfbClient value) {
      rfbClient = value;
      pointerButtons = 0;
    }

    @Override
    protected void onDraw(Canvas target) {
      super.onDraw(target);
      target.drawColor(Color.BLACK);
      Bitmap image = framebuffer;
      if (image == null || image.isRecycled()) return;
      float scale = Math.min(getWidth() / (float) image.getWidth(),
          getHeight() / (float) image.getHeight());
      float left = (getWidth() - image.getWidth() * scale) / 2f;
      float top = (getHeight() - image.getHeight() * scale) / 2f;
      synchronized (image) {
        target.drawBitmap(image, null,
            new android.graphics.RectF(left, top, left + image.getWidth() * scale,
                top + image.getHeight() * scale), paint);
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      RfbClient current = rfbClient;
      Bitmap image = framebuffer;
      if (current == null || image == null) return true;
      requestFocus();
      float scale = Math.min(getWidth() / (float) image.getWidth(),
          getHeight() / (float) image.getHeight());
      float left = (getWidth() - image.getWidth() * scale) / 2f;
      float top = (getHeight() - image.getHeight() * scale) / 2f;
      int x = Math.max(0, Math.min(image.getWidth() - 1,
          Math.round((event.getX() - left) / scale)));
      int y = Math.max(0, Math.min(image.getHeight() - 1,
          Math.round((event.getY() - top) / scale)));
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          pointerButtons = 1;
          current.sendPointer(pointerButtons, x, y);
          return true;
        case MotionEvent.ACTION_MOVE:
          current.sendPointer(pointerButtons, x, y);
          return true;
        case MotionEvent.ACTION_POINTER_DOWN:
          pointerButtons = 4;
          current.sendPointer(pointerButtons, x, y);
          return true;
        case MotionEvent.ACTION_POINTER_UP:
          pointerButtons = 0;
          current.sendPointer(pointerButtons, x, y);
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          pointerButtons = 0;
          current.sendPointer(pointerButtons, x, y);
          return true;
        default:
          return true;
      }
    }
  }
}

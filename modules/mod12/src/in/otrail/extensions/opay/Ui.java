package in.otrail.extensions.opay;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Neon oTraiL widget factory. Colors follow the downloaded-module palette. */
final class Ui {
  static final int BG = 0xff0b0e13, SURFACE = 0xff12161d, SURFACE_ALT = 0xff171c24,
      TEXT = 0xfff8fafc, MUTED = 0xff8494a3, CYAN = 0xff00d4ff, GREEN = 0xff55ff55,
      RED = 0xffff3838, AMBER = 0xffffb300, PURPLE = 0xff9b59ff, BORDER = 0xff2a3555,
      PINK = 0xffff4162;

  private Ui() {}

  static LinearLayout column(Activity activity) {
    LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  static LinearLayout row(Activity activity) {
    LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.HORIZONTAL);
    layout.setGravity(Gravity.CENTER_VERTICAL);
    return layout;
  }

  static LinearLayout card(Activity activity) {
    LinearLayout layout = column(activity);
    layout.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
    layout.setBackground(panel(activity, SURFACE, BORDER, 9));
    return layout;
  }

  static TextView label(Activity activity, String text, float size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setPadding(0, dp(activity, 2), 0, dp(activity, 2));
    return view;
  }

  static TextView mono(Activity activity, String text, float size, int color) {
    TextView view = label(activity, text, size, color);
    view.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
    return view;
  }

  static EditText input(Activity activity, String hint) {
    EditText input = new EditText(activity);
    input.setHint(hint);
    input.setHintTextColor(MUTED);
    input.setTextColor(TEXT);
    input.setTextSize(12);
    input.setSingleLine(true);
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    input.setBackground(panel(activity, SURFACE_ALT, BORDER, 8));
    input.setPadding(dp(activity, 10), 0, dp(activity, 10), 0);
    return input;
  }

  static EditText numberInput(Activity activity, String hint) {
    EditText input = input(activity, hint);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    return input;
  }

  static Button button(Activity activity, String text, Runnable action) {
    Button button = new Button(activity);
    button.setText(text);
    button.setTextSize(10);
    button.setTextColor(TEXT);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
    button.setBackground(panel(activity, SURFACE_ALT, BORDER, 7));
    button.setOnClickListener(view -> action.run());
    return button;
  }

  static Button primary(Activity activity, String text, Runnable action) {
    Button button = button(activity, text, action);
    button.setTextColor(0xff001a00);
    button.setTextSize(11);
    button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    button.setBackground(panel(activity, GREEN, GREEN, 8));
    return button;
  }

  static Button accent(Activity activity, String text, Runnable action) {
    Button button = button(activity, text, action);
    button.setTextColor(0xff001a1a);
    button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    button.setBackground(panel(activity, CYAN, CYAN, 8));
    return button;
  }

  static void paintChip(Button button, boolean active) {
    if (button == null) return;
    Activity activity = (Activity) button.getContext();
    button.setTextColor(active ? 0xff000000 : TEXT);
    button.setBackground(panel(activity, active ? CYAN : SURFACE_ALT, active ? CYAN : BORDER, 7));
  }

  static LinearLayout.LayoutParams weight(float weight) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
    params.setMargins(2, 2, 2, 2);
    return params;
  }

  static LinearLayout.LayoutParams full(int heightDp, Activity activity) {
    return new LinearLayout.LayoutParams(-1, heightDp <= 0 ? -2 : dp(activity, heightDp));
  }

  static LinearLayout.LayoutParams wrap() {
    return new LinearLayout.LayoutParams(-2, -2);
  }

  static GradientDrawable panel(Activity activity, int color, int stroke, int radiusDp) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(activity, radiusDp));
    background.setStroke(dp(activity, 1), stroke);
    return background;
  }

  static int dp(Activity activity, float value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  static View spacer(Activity activity, int heightDp) {
    View view = new View(activity);
    view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(activity, heightDp)));
    return view;
  }

  static void addGap(LinearLayout parent, Activity activity, int heightDp) {
    parent.addView(spacer(activity, heightDp));
  }

  static void message(LinearLayout parent, Activity activity, String text) {
    parent.removeAllViews();
    TextView view = label(activity, text, 11, MUTED);
    view.setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 8));
    parent.addView(view);
  }
}

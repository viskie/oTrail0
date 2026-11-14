package in.otrail;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.List;
import java.util.Objects;

/** Shared styling for app-owned dialogs and modal progress popups. */
final class AppDialogs extends AlertDialog.Builder {
  private final Style style;

  AppDialogs(Context context) {
    this(context, Style.defaults());
  }

  AppDialogs(Context context, Style style) {
    super(context);
    this.style = Objects.requireNonNull(style);
  }

  static ProgressDialog progress(Context context) {
    return progress(context, Style.defaults());
  }

  static ProgressDialog progress(Context context, Style style) {
    ProgressDialog dialog = new ProgressDialog(context);
    Style selected = Objects.requireNonNull(style);
    dialog.setOnShowListener(ignored -> apply(dialog, selected));
    return dialog;
  }

  static Style defaultStyle() {
    return Style.defaults();
  }

  @Override
  public AlertDialog create() {
    AlertDialog dialog = super.create();
    dialog.setOnShowListener(ignored -> apply(dialog, style));
    return dialog;
  }

  private static void apply(AlertDialog dialog, Style style) {
    if (dialog.getWindow() != null) {
      View decor = dialog.getWindow().getDecorView();
      AppStyle.nativeTextTree(decor);
      styleContent(decor, style);
      dialog.getWindow().setBackgroundDrawable(panel(decor, style));
    }

    int titleId =
        dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
    TextView title = dialog.findViewById(titleId);
    if (title != null) {
      title.setTextColor(style.titleColor);
      title.setTypeface(Typeface.create(style.titleFont, style.titleFontStyle));
      title.setTextSize(style.titleSizeSp * UiConfig.DIALOG_TEXT_SCALE);
    }

    for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
        AlertDialog.BUTTON_NEUTRAL}) {
      Button button = dialog.getButton(which);
      if (button != null) styleButton(button, style);
    }

    ListView list = dialog.getListView();
    if (list != null && list.getAdapter() != null
        && !(list.getAdapter() instanceof StyledListAdapter)) {
      int singleChecked = list.getCheckedItemPosition();
      android.util.SparseBooleanArray checked = list.getCheckedItemPositions();
      android.util.SparseBooleanArray checkedCopy = new android.util.SparseBooleanArray();
      if (checked != null) {
        for (int i = 0; i < checked.size(); i++) {
          int position = checked.keyAt(i);
          if (checked.valueAt(i)) checkedCopy.put(position, true);
        }
      }
      list.setAdapter(new StyledListAdapter(list.getAdapter(), style));
      if (singleChecked >= 0) list.setItemChecked(singleChecked, true);
      else {
        for (int i = 0; i < checkedCopy.size(); i++) {
          list.setItemChecked(checkedCopy.keyAt(i), checkedCopy.valueAt(i));
        }
      }
    }
  }

  static void styleContent(View view, Style style) {
    if (view instanceof Button button && !(view instanceof CompoundButton)) {
      styleButton(button, style);
    } else if (view instanceof TextView text) {
      text.setTextColor(style.bodyColor);
      text.setLinkTextColor(style.linkColor);
      text.setTextSize(style.bodySizeSp * UiConfig.DIALOG_TEXT_SCALE);
      text.setTypeface(bodyTypeface(text.getTypeface(), style));
      if (text instanceof android.widget.EditText) text.setHintTextColor(style.hintColor);
    }

    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        styleContent(group.getChildAt(i), style);
      }
    }
  }

  static ArrayAdapter<String> choices(Context context, List<String> values, Style style) {
    return new ArrayAdapter<String>(
        context, android.R.layout.simple_spinner_dropdown_item, values) {
      @Override
      public View getView(int position, View recycled, ViewGroup parent) {
        View view = super.getView(position, recycled, parent);
        styleContent(view, style);
        return view;
      }

      @Override
      public View getDropDownView(int position, View recycled, ViewGroup parent) {
        View view = super.getDropDownView(position, recycled, parent);
        styleContent(view, style);
        view.setBackgroundColor(style.surfaceColor);
        return view;
      }
    };
  }

  private static void styleButton(Button button, Style style) {
    button.setTextColor(
        new ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {}},
            new int[] {style.disabledButtonTextColor, style.buttonTextColor}));
    button.setTypeface(Typeface.create(style.buttonFont, style.buttonFontStyle));
    button.setTextSize(style.buttonSizeSp * UiConfig.DIALOG_TEXT_SCALE);
  }

  private static Typeface bodyTypeface(Typeface existing, Style style) {
    int inherited = existing == null ? Typeface.NORMAL : existing.getStyle();
    return switch ((inherited | style.bodyFontStyle) & 3) {
      case 3 -> Typeface.create(style.bodyFont, Typeface.BOLD_ITALIC);
      case 2 -> Typeface.create(style.bodyFont, Typeface.ITALIC);
      case 1 -> Typeface.create(style.bodyFont, Typeface.BOLD);
      default -> Typeface.create(style.bodyFont, Typeface.NORMAL);
    };
  }

  private static Drawable panel(View view, Style style) {
    float density = view.getResources().getDisplayMetrics().density;
    float radius = style.cornerRadiusDp * density;
    GradientDrawable fill =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] {
              UiConfig.VIBRANT_FINISH
                  ? mix(style.surfaceColor, style.accentColor, style.panelTintMix)
                  : style.surfaceColor,
              style.surfaceColor
            });
    fill.setCornerRadius(radius);
    if (UiConfig.VIBRANT_FINISH) {
      fill.setStroke(Math.max(1, Math.round(UiConfig.DIALOG_BORDER_WIDTH_DP * density)),
          style.accentColor);
    } else {
      return fill;
    }

    GradientDrawable edge = new GradientDrawable();
    edge.setColor(style.accentColor);
    edge.setCornerRadius(radius);
    LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[] {edge, fill});
    layers.setLayerInset(
        1, Math.round(UiConfig.DIALOG_EDGE_WIDTH_DP * density), 0, 0, 0);
    return layers;
  }

  private static int mix(int from, int to, float amount) {
    float t = Math.max(0, Math.min(1, amount));
    return Color.argb(
        Math.round(Color.alpha(from) * (1 - t) + Color.alpha(to) * t),
        Math.round(Color.red(from) * (1 - t) + Color.red(to) * t),
        Math.round(Color.green(from) * (1 - t) + Color.green(to) * t),
        Math.round(Color.blue(from) * (1 - t) + Color.blue(to) * t));
  }

  static final class Style {
    final int titleColor;
    final String titleFont;
    final int titleFontStyle;
    final int titleSizeSp;
    final int bodyColor;
    final String bodyFont;
    final int bodyFontStyle;
    final int bodySizeSp;
    final int linkColor;
    final int hintColor;
    final int buttonTextColor;
    final int disabledButtonTextColor;
    final String buttonFont;
    final int buttonFontStyle;
    final int buttonSizeSp;
    final int surfaceColor;
    final int accentColor;
    final float panelTintMix;
    final int cornerRadiusDp;

    private Style(Builder builder) {
      titleColor = builder.titleColor;
      titleFont = builder.titleFont;
      titleFontStyle = builder.titleFontStyle;
      titleSizeSp = builder.titleSizeSp;
      bodyColor = builder.bodyColor;
      bodyFont = builder.bodyFont;
      bodyFontStyle = builder.bodyFontStyle;
      bodySizeSp = builder.bodySizeSp;
      linkColor = builder.linkColor;
      hintColor = builder.hintColor;
      buttonTextColor = builder.buttonTextColor;
      disabledButtonTextColor = builder.disabledButtonTextColor;
      buttonFont = builder.buttonFont;
      buttonFontStyle = builder.buttonFontStyle;
      buttonSizeSp = builder.buttonSizeSp;
      surfaceColor = builder.surfaceColor;
      accentColor = builder.accentColor;
      panelTintMix = builder.panelTintMix;
      cornerRadiusDp = builder.cornerRadiusDp;
    }

    static Style defaults() {
      return builder().build();
    }

    static Builder builder() {
      return new Builder();
    }

    static final class Builder {
      private int titleColor = UiConfig.DIALOG_TITLE_COLOR;
      private String titleFont = UiConfig.DIALOG_TITLE_FONT;
      private int titleFontStyle = UiConfig.DIALOG_TITLE_FONT_STYLE;
      private int titleSizeSp = UiConfig.DIALOG_TITLE_SIZE_SP;
      private int bodyColor = UiConfig.DIALOG_BODY_COLOR;
      private String bodyFont = UiConfig.DIALOG_BODY_FONT;
      private int bodyFontStyle = UiConfig.DIALOG_BODY_FONT_STYLE;
      private int bodySizeSp = UiConfig.DIALOG_BODY_SIZE_SP;
      private int linkColor = UiConfig.DIALOG_LINK_COLOR;
      private int hintColor = UiConfig.DIALOG_HINT_COLOR;
      private int buttonTextColor = UiConfig.DIALOG_BUTTON_TEXT_COLOR;
      private int disabledButtonTextColor = UiConfig.DIALOG_BUTTON_DISABLED_TEXT_COLOR;
      private String buttonFont = UiConfig.DIALOG_BUTTON_FONT;
      private int buttonFontStyle = UiConfig.DIALOG_BUTTON_FONT_STYLE;
      private int buttonSizeSp = UiConfig.DIALOG_BUTTON_SIZE_SP;
      private int surfaceColor = UiConfig.DIALOG_SURFACE;
      private int accentColor = UiConfig.DIALOG_ACCENT;
      private float panelTintMix = UiConfig.DIALOG_PANEL_TINT_MIX;
      private int cornerRadiusDp = UiConfig.DIALOG_CORNER_RADIUS_DP;

      Builder titleColor(int color) { titleColor = color; return this; }
      Builder titleFont(String font) { titleFont = Objects.requireNonNull(font); return this; }
      Builder titleFontStyle(int style) { titleFontStyle = style; return this; }
      Builder titleSizeSp(int size) { titleSizeSp = positive(size); return this; }
      Builder bodyColor(int color) { bodyColor = color; return this; }
      Builder bodyFont(String font) { bodyFont = Objects.requireNonNull(font); return this; }
      Builder bodyFontStyle(int style) { bodyFontStyle = style; return this; }
      Builder bodySizeSp(int size) { bodySizeSp = positive(size); return this; }
      Builder linkColor(int color) { linkColor = color; return this; }
      Builder hintColor(int color) { hintColor = color; return this; }
      Builder buttonTextColor(int color) { buttonTextColor = color; return this; }
      Builder disabledButtonTextColor(int color) {
        disabledButtonTextColor = color;
        return this;
      }
      Builder buttonFont(String font) { buttonFont = Objects.requireNonNull(font); return this; }
      Builder buttonFontStyle(int style) { buttonFontStyle = style; return this; }
      Builder buttonSizeSp(int size) { buttonSizeSp = positive(size); return this; }
      Builder surfaceColor(int color) { surfaceColor = color; return this; }
      Builder accentColor(int color) { accentColor = color; return this; }
      Builder panelTintMix(float mix) {
        if (!Float.isFinite(mix) || mix < 0 || mix > 1) {
          throw new IllegalArgumentException("Dialog panel tint must be between 0 and 1");
        }
        panelTintMix = mix;
        return this;
      }
      Builder cornerRadiusDp(int radius) { cornerRadiusDp = positive(radius); return this; }

      Style build() {
        return new Style(this);
      }

      private static int positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("Dialog dimensions must be positive");
        return value;
      }
    }
  }

  private static final class StyledListAdapter extends BaseAdapter {
    private final ListAdapter delegate;
    private final Style style;
    private final DataSetObserver observer =
        new DataSetObserver() {
          @Override public void onChanged() { notifyDataSetChanged(); }
          @Override public void onInvalidated() { notifyDataSetInvalidated(); }
        };

    StyledListAdapter(ListAdapter delegate, Style style) {
      this.delegate = delegate;
      this.style = style;
      delegate.registerDataSetObserver(observer);
    }

    @Override public int getCount() { return delegate.getCount(); }
    @Override public Object getItem(int position) { return delegate.getItem(position); }
    @Override public long getItemId(int position) { return delegate.getItemId(position); }
    @Override public boolean hasStableIds() { return delegate.hasStableIds(); }
    @Override public int getItemViewType(int position) { return delegate.getItemViewType(position); }
    @Override public int getViewTypeCount() { return delegate.getViewTypeCount(); }
    @Override public boolean areAllItemsEnabled() { return delegate.areAllItemsEnabled(); }
    @Override public boolean isEnabled(int position) { return delegate.isEnabled(position); }
    @Override public boolean isEmpty() { return delegate.isEmpty(); }

    @Override
    public View getView(int position, View recycled, ViewGroup parent) {
      View row = delegate.getView(position, recycled, parent);
      styleContent(row, style);
      return row;
    }
  }
}

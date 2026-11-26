package in.otrail.modules;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.*;
import android.widget.TextView;
import java.util.function.Consumer;

/** A transcript that accepts terminal input without editing the displayed output. */
public final class TerminalSurface extends TextView implements android.hardware.input.InputManager.InputDeviceListener {
  private final Consumer<String> send;

  public TerminalSurface(Context context, Consumer<String> send) {
    super(context);
    this.send = send;
    setFocusableInTouchMode(true);
    setOnClickListener(v -> {
      requestFocus();
      InputMethodManager ime = context.getSystemService(InputMethodManager.class);
      if (hasKeyboard()) ime.hideSoftInputFromWindow(getWindowToken(), 0);
      else ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    });
  }

  private boolean hasKeyboard() {
      for (int id : InputDevice.getDeviceIds()) {
        InputDevice device = InputDevice.getDevice(id);
        if (device != null && !device.isVirtual() && device.isEnabled()
            && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
          return true;
        }
      }
      return false;
  }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    getContext().getSystemService(android.hardware.input.InputManager.class)
        .registerInputDeviceListener(this, getHandler());
  }

  @Override protected void onDetachedFromWindow() {
    getContext().getSystemService(android.hardware.input.InputManager.class).unregisterInputDeviceListener(this);
    super.onDetachedFromWindow();
  }

  @Override public void onInputDeviceAdded(int id) { onInputDeviceChanged(id); }
  @Override public void onInputDeviceRemoved(int id) {}
  @Override public void onInputDeviceChanged(int id) {
    if (hasFocus() && hasKeyboard()) getContext().getSystemService(InputMethodManager.class)
        .hideSoftInputFromWindow(getWindowToken(), 0);
  }

  @Override public boolean onCheckIsTextEditor() { return true; }

  @Override public InputConnection onCreateInputConnection(EditorInfo info) {
    info.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_DONE;
    return new BaseInputConnection(this, true) {
      @Override public boolean commitText(CharSequence text, int cursor) {
        getEditable().clear();
        send.accept(text.toString());
        return true;
      }
      @Override public boolean finishComposingText() {
        String pending = getEditable().toString();
        getEditable().clear();
        if (!pending.isEmpty()) send.accept(pending);
        return true;
      }
      @Override public boolean deleteSurroundingText(int before, int after) {
        if (getEditable().length() > 0) return super.deleteSurroundingText(before, after);
        for (int i = 0; i < before; i++) send.accept("\u007f");
        for (int i = 0; i < after; i++) send.accept("\u001b[3~");
        return true;
      }
      @Override public boolean performEditorAction(int action) { send.accept("\n"); return true; }
    };
  }

  @Override public boolean onKeyDown(int key, KeyEvent event) {
    String value;
    switch (key) {
      case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: value = "\n"; break;
      case KeyEvent.KEYCODE_DEL: value = "\u007f"; break;
      case KeyEvent.KEYCODE_TAB: value = "\t"; break;
      case KeyEvent.KEYCODE_ESCAPE: value = "\u001b"; break;
      case KeyEvent.KEYCODE_DPAD_UP: value = "\u001b[A"; break;
      case KeyEvent.KEYCODE_DPAD_DOWN: value = "\u001b[B"; break;
      case KeyEvent.KEYCODE_DPAD_RIGHT: value = "\u001b[C"; break;
      case KeyEvent.KEYCODE_DPAD_LEFT: value = "\u001b[D"; break;
      default:
        int c = event.getUnicodeChar();
        if (event.isCtrlPressed() && key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z)
          c = key - KeyEvent.KEYCODE_A + 1;
        if (c <= 0) return super.onKeyDown(key, event);
        value = new String(Character.toChars(c));
    }
    send.accept(value);
    return true;
  }
}

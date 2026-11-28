package in.otrail.extensions.phone;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.modules.ModuleV1;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Large-touch car dialer: keypad, contacts and call history with in-module runtime permissions.
 * Calls are handed to the system telephony stack, which owns the in-call screen.
 */
public final class PhoneModule implements ModuleV1 {
  private static final int BG = 0xff0b0e13, SURFACE = 0xff12161d, SURFACE_ALT = 0xff171c24,
      TEXT = 0xfff8fafc, MUTED = 0xff8494a3, CYAN = 0xff00d4ff, GREEN = 0xff55ff55,
      RED = 0xffff3838, AMBER = 0xffffb300, BORDER = 0xff2a3555;
  private static final int REQUEST = 9101;
  private static final String[] PERMISSIONS = {
      Manifest.permission.CALL_PHONE,
      Manifest.permission.READ_CONTACTS,
      Manifest.permission.READ_CALL_LOG
  };

  private Activity activity;
  private Host host;
  private LinearLayout root;
  private FrameLayout container;
  private LinearLayout dialScreen, contactsScreen, recentsScreen, tabRow, grantRow;
  private TextView numberView, status;
  private LinearLayout contactsList, recentsList;
  private EditText search;
  private Button dialTab, contactsTab, recentsTab;
  private String tab = "dial";
  private String number = "";
  private int requestCode = REQUEST;
  private boolean loadingContacts;
  private final Handler main = new Handler(Looper.getMainLooper());

  private static final class Contact {
    final String name, number;

    Contact(String name, String number) {
      this.name = name == null ? "" : name;
      this.number = number == null ? "" : number;
    }
  }

  private static final class Call {
    final String name, number;
    final int type;
    final long date;

    Call(String name, String number, int type, long date) {
      this.name = name == null ? "" : name;
      this.number = number == null ? "" : number;
      this.type = type;
      this.date = date;
    }
  }

  @Override public String id() {
    return "mod9";
  }

  @Override public View create(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setPadding(dp(9), dp(6), dp(9), dp(9));
    root.addView(header(), new LinearLayout.LayoutParams(-1, -2));
    container = new FrameLayout(activity);
    dialScreen = buildDial();
    contactsScreen = buildContacts();
    recentsScreen = buildRecents();
    Container.add(container, dialScreen);
    Container.add(container, contactsScreen);
    Container.add(container, recentsScreen);
    root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1f));
    selectTab(tab);
    if (!missing().isEmpty()) showGrant(true);
    return root;
  }

  /** Tiny helper so the module stays readable without a layout inflater. */
  private static final class Container {
    static void add(FrameLayout parent, View child) {
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
      parent.addView(child, params);
    }
  }

  @Override public void resume() {
    if (activity == null) return;
    if (missing().isEmpty()) refreshCurrent();
  }

  @Override public void permissionResult(int request, String[] permissions, int[] grants) {
    if (request != requestCode) return;
    int denied = 0;
    for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) denied++;
    if (denied == 0) {
      showGrant(false);
      setStatus("Phone access granted.", GREEN);
      refreshCurrent();
    } else {
      showGrant(true);
      setStatus(denied + " permission(s) denied · tap ALLOW PHONE ACCESS", RED);
      hostLog("permission-denied", denied + " of " + grants.length + " denied");
    }
  }

  // ---------------------------------------------------------------- permissions

  private List<String> missing() {
    List<String> missing = new ArrayList<>();
    if (activity == null) return missing;
    for (String permission : PERMISSIONS)
      if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
        missing.add(permission);
    return missing;
  }

  private boolean granted(String permission) {
    return activity != null && activity.checkSelfPermission(permission)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void showGrant(boolean visible) {
    if (grantRow != null) grantRow.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private void requestPhonePermissions() {
    if (activity == null) return;
    try {
      activity.requestPermissions(PERMISSIONS, requestCode);
      setStatus("Allow phone, contacts and call history…", AMBER);
    } catch (Exception error) {
      hostLog("permission-failed", error);
      setStatus("This host build does not declare phone permissions.", RED);
    }
  }

  // ---------------------------------------------------------------- screens

  private LinearLayout buildDial() {
    LinearLayout screen = baseScreen();
    numberView = label("", 30, TEXT);
    numberView.setGravity(Gravity.CENTER);
    numberView.setSingleLine(true);
    numberView.setTextSize(30);
    numberView.setPadding(dp(8), dp(10), dp(8), dp(4));
    screen.addView(numberView, new LinearLayout.LayoutParams(-1, -2));

    TextView hint = label("Tap digits, then CALL", 9, MUTED);
    hint.setGravity(Gravity.CENTER);
    screen.addView(hint);

    GridLayout pad = new GridLayout(activity);
    pad.setColumnCount(3);
    pad.setPadding(0, dp(4), 0, 0);
    String[][] keys = {{"1", ""}, {"2", "ABC"}, {"3", "DEF"}, {"4", "GHI"}, {"5", "JKL"},
        {"6", "MNO"}, {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"}, {"*", ""}, {"0", "+"},
        {"#", ""}};
    for (String[] key : keys) {
      Button keyButton = key(key[0], key[1]);
      GridLayout.LayoutParams params = new GridLayout.LayoutParams();
      params.width = 0;
      params.height = dp(56);
      params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
      params.setMargins(dp(2), dp(2), dp(2), dp(2));
      pad.addView(keyButton, params);
    }
    screen.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1f));

    LinearLayout actions = new LinearLayout(activity);
    actions.setPadding(0, dp(3), 0, 0);
    actions.addView(chip("⌫", this::backspace), weight(1));
    Button call = chip("CALL", this::callTyped);
    call.setTextColor(0xff001a00);
    call.setTextSize(16);
    call.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    call.setBackground(panel(GREEN, GREEN, 10));
    actions.addView(call, weight(3));
    screen.addView(actions, new LinearLayout.LayoutParams(-1, dp(58)));
    return screen;
  }

  private LinearLayout buildContacts() {
    LinearLayout screen = baseScreen();
    search = new EditText(activity);
    search.setHint("Search contacts");
    search.setHintTextColor(MUTED);
    search.setTextColor(TEXT);
    search.setTextSize(12);
    search.setSingleLine(true);
    search.setInputType(InputType.TYPE_CLASS_TEXT);
    search.setBackground(panel(SURFACE_ALT, BORDER, 8));
    search.setPadding(dp(10), 0, dp(10), 0);
    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(Editable s) {
        loadContacts(s.toString().trim());
      }
    });
    screen.addView(search, new LinearLayout.LayoutParams(-1, dp(44)));

    contactsList = new LinearLayout(activity);
    contactsList.setOrientation(LinearLayout.VERTICAL);
    setMessage(contactsList, "Allow contacts to list phone numbers here.");
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(contactsList, new ScrollView.LayoutParams(-1, -2));
    screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    return screen;
  }

  private LinearLayout buildRecents() {
    LinearLayout screen = baseScreen();
    recentsList = new LinearLayout(activity);
    recentsList.setOrientation(LinearLayout.VERTICAL);
    setMessage(recentsList, "Recent calls appear here.");
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(recentsList, new ScrollView.LayoutParams(-1, -2));
    screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    return screen;
  }

  private LinearLayout baseScreen() {
    LinearLayout screen = new LinearLayout(activity);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(BG);
    screen.setPadding(dp(9), dp(6), dp(9), dp(9));
    return screen;
  }

  private View header() {
    LinearLayout header = new LinearLayout(activity);
    header.setOrientation(LinearLayout.VERTICAL);

    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView titleView = label("PHONE", 18, CYAN);
    titleView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    row.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
    status = label("", 10, MUTED);
    status.setGravity(Gravity.END);
    status.setMaxLines(2);
    row.addView(status, new LinearLayout.LayoutParams(-2, -2));
    header.addView(row);

    tabRow = new LinearLayout(activity);
    tabRow.setPadding(0, dp(3), 0, dp(3));
    dialTab = chip("DIAL", () -> selectTab("dial"));
    contactsTab = chip("CONTACTS", () -> selectTab("contacts"));
    recentsTab = chip("RECENTS", () -> selectTab("recents"));
    tabRow.addView(dialTab, weight(1));
    tabRow.addView(contactsTab, weight(1));
    tabRow.addView(recentsTab, weight(1));
    header.addView(tabRow);

    grantRow = new LinearLayout(activity);
    Button grant = chip("ALLOW PHONE ACCESS", this::requestPhonePermissions);
    grant.setTextColor(0xff000000);
    grant.setBackground(panel(AMBER, AMBER, 8));
    grantRow.addView(grant, new LinearLayout.LayoutParams(-1, dp(36)));
    grantRow.setVisibility(View.GONE);
    header.addView(grantRow);
    return header;
  }

  private void selectTab(String selected) {
    tab = selected;
    paintTab(dialTab, selected.equals("dial"));
    paintTab(contactsTab, selected.equals("contacts"));
    paintTab(recentsTab, selected.equals("recents"));
    show(selected.equals("dial") ? dialScreen
        : selected.equals("contacts") ? contactsScreen : recentsScreen);
    refreshCurrent();
  }

  private void show(View screen) {
    for (int index = 0; index < container.getChildCount(); index++)
      container.getChildAt(index).setVisibility(
          container.getChildAt(index) == screen ? View.VISIBLE : View.GONE);
  }

  private void paintTab(Button button, boolean active) {
    if (button == null) return;
    button.setTextColor(active ? 0xff000000 : TEXT);
    button.setBackground(panel(active ? CYAN : SURFACE_ALT, active ? CYAN : BORDER, 7));
  }

  // ---------------------------------------------------------------- dialing

  private void append(String key) {
    if (number.length() >= 24) return;
    number += key;
    numberView.setText(number);
  }

  private void backspace() {
    if (number.isEmpty()) return;
    number = number.substring(0, number.length() - 1);
    numberView.setText(number);
  }

  private void callTyped() {
    call(number);
  }

  private void call(String raw) {
    String target = raw == null ? "" : raw.replaceAll("[^0-9+*#,;]", "");
    if (target.isEmpty()) {
      setStatus("Enter a number first.", AMBER);
      return;
    }
    boolean direct = granted(Manifest.permission.CALL_PHONE);
    try {
      Intent intent = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL,
          Uri.fromParts("tel", target, null));
      activity.startActivity(intent);
      setStatus((direct ? "Calling " : "Opening dialer for ") + target, GREEN);
    } catch (Exception error) {
      hostLog("call-failed", error);
      setStatus("Call failed: " + message(error), RED);
    }
  }

  private void refreshCurrent() {
    try {
      if (tab.equals("contacts")) loadContacts(search == null ? "" : search.getText().toString().trim());
      else if (tab.equals("recents")) loadRecents();
    } catch (RuntimeException error) {
      // Every module failure is forwarded to the LOGGER module (mod6).
      hostLog("refresh-failed", error);
      setStatus("Phone view failed: " + message(error), RED);
    }
  }

  private void loadContacts(String query) {
    if (activity == null || contactsList == null) return;
    if (loadingContacts) return;
    if (!granted(Manifest.permission.READ_CONTACTS)) {
      setMessage(contactsList, "Allow contacts to list phone numbers here.\n\nDIAL still works without it.");
      showGrant(true);
      return;
    }
    loadingContacts = true;
    setMessage(contactsList, "Reading contacts…");
    final String filter = query == null ? "" : query.toLowerCase(Locale.US);
    new Thread(() -> {
      List<Contact> found = new ArrayList<>();
      String problem = null;
      Cursor cursor = null;
      try {
        ContentResolver resolver = activity.getContentResolver();
        cursor = resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER},
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        if (cursor != null) {
          while (cursor.moveToNext() && found.size() < 400) {
            String name = cursor.getString(0);
            String phone = cursor.getString(1);
            if (TextUtils.isEmpty(phone)) continue;
            if (!filter.isEmpty()
                && (name == null || !name.toLowerCase(Locale.US).contains(filter))
                && !phone.toLowerCase(Locale.US).contains(filter)) continue;
            found.add(new Contact(name, phone));
          }
        }
      } catch (Exception error) {
        problem = message(error);
        hostLog("contacts-failed", error);
      } finally {
        if (cursor != null) cursor.close();
      }
      final List<Contact> contacts = found;
      final String failure = problem;
      main.post(() -> {
        loadingContacts = false;
        if (activity == null || contactsList == null) return;
        if (failure != null) {
          setMessage(contactsList, "Could not read contacts: " + failure);
          return;
        }
        if (contacts.isEmpty()) {
          setMessage(contactsList, "No contacts match \"" + filter + "\".");
          return;
        }
        contactsList.removeAllViews();
        for (Contact contact : contacts) contactsList.addView(contactRow(contact));
        if (filter.isEmpty()) setStatus(contacts.size() + " contacts", MUTED);
      });
    }, "phone-contacts").start();
  }

  private View contactRow(Contact contact) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(10), dp(8), dp(10), dp(8));
    row.setBackground(panel(SURFACE, BORDER, 8));
    TextView name = label(contact.name.isEmpty() ? contact.number : contact.name, 14, TEXT);
    name.setSingleLine(true);
    row.addView(name);
    if (!contact.name.isEmpty()) {
      TextView phone = label(contact.number, 11, CYAN);
      row.addView(phone);
    }
    row.setOnClickListener(view -> {
      number = contact.number;
      numberView.setText(number);
      selectTab("dial");
      callTyped();
    });
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(2), 0, dp(2));
    row.setLayoutParams(params);
    return row;
  }

  private void loadRecents() {
    if (activity == null || recentsList == null) return;
    if (!granted(Manifest.permission.READ_CALL_LOG)) {
      setMessage(recentsList, "Allow call history to list recent calls.\n\nDIAL still works without it.");
      showGrant(true);
      return;
    }
    setMessage(recentsList, "Reading call history…");
    new Thread(() -> {
      List<Call> calls = new ArrayList<>();
      String problem = null;
      Cursor cursor = null;
      try {
        cursor = activity.getContentResolver().query(CallLog.Calls.CONTENT_URI,
            new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                CallLog.Calls.DATE}, null, null, CallLog.Calls.DATE + " DESC");
        if (cursor != null) while (cursor.moveToNext() && calls.size() < 80)
          calls.add(new Call(cursor.getString(1), cursor.getString(0), cursor.getInt(2),
              cursor.getLong(3)));
      } catch (Exception error) {
        problem = message(error);
        hostLog("recents-failed", error);
      } finally {
        if (cursor != null) cursor.close();
      }
      final List<Call> history = calls;
      final String failure = problem;
      main.post(() -> {
        if (activity == null || recentsList == null) return;
        if (failure != null) {
          setMessage(recentsList, "Could not read call history: " + failure);
          return;
        }
        if (history.isEmpty()) {
          setMessage(recentsList, "No calls yet.");
          return;
        }
        recentsList.removeAllViews();
        for (Call call : history) recentsList.addView(callRow(call));
      });
    }, "phone-recents").start();
  }

  private View callRow(Call call) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), dp(7), dp(10), dp(7));
    row.setBackground(panel(SURFACE, BORDER, 8));

    String kind = call.type == CallLog.Calls.INCOMING_TYPE ? "IN"
        : call.type == CallLog.Calls.OUTGOING_TYPE ? "OUT"
        : call.type == CallLog.Calls.MISSED_TYPE ? "MISSED" : "CALL";
    int color = call.type == CallLog.Calls.MISSED_TYPE ? RED
        : call.type == CallLog.Calls.OUTGOING_TYPE ? GREEN : CYAN;
    TextView badge = label(kind, 9, color);
    badge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    badge.setPadding(0, 0, dp(10), 0);
    row.addView(badge);

    LinearLayout text = new LinearLayout(activity);
    text.setOrientation(LinearLayout.VERTICAL);
    TextView name = label(call.name.isEmpty() ? call.number : call.name, 14, TEXT);
    name.setSingleLine(true);
    text.addView(name);
    TextView detail = label(call.number + " · " + when(call.date), 10, MUTED);
    detail.setSingleLine(true);
    text.addView(detail);
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

    TextView callNow = label("CALL", 11, GREEN);
    callNow.setPadding(dp(10), dp(4), dp(4), dp(4));
    row.addView(callNow);
    row.setOnClickListener(view -> call(call.number));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(2), 0, dp(2));
    row.setLayoutParams(params);
    return row;
  }

  private String when(long time) {
    long age = System.currentTimeMillis() - time;
    if (age < 60_000L) return "just now";
    if (age < 3_600_000L) return (age / 60_000L) + "m ago";
    if (age < 86_400_000L) return (age / 3_600_000L) + "h ago";
    return new SimpleDateFormat("dd MMM", Locale.US).format(new Date(time));
  }

  private void setMessage(LinearLayout list, String text) {
    if (list == null) return;
    list.removeAllViews();
    TextView message = label(text, 11, MUTED);
    message.setPadding(dp(4), dp(6), dp(4), dp(6));
    list.addView(message);
  }

  private void setStatus(String text, int color) {
    if (status == null) return;
    status.setText(text);
    status.setTextColor(color);
  }

  // ---------------------------------------------------------------- widgets

  private Button key(String digit, String letters) {
    Button button = new Button(activity);
    button.setText(letters.isEmpty() ? digit : digit + "\n" + letters);
    button.setAllCaps(false);
    button.setTextSize(letters.isEmpty() ? 20 : 15);
    button.setTextColor(TEXT);
    button.setPadding(0, 0, 0, 0);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setBackground(panel(SURFACE, BORDER, 9));
    button.setOnClickListener(view -> append(digit));
    return button;
  }

  private Button chip(String text, Runnable action) {
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
    button.setPadding(dp(4), 0, dp(4), 0);
    button.setBackground(panel(SURFACE_ALT, BORDER, 7));
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setPadding(0, dp(2), 0, dp(2));
    return view;
  }

  private GradientDrawable panel(int color, int stroke, int radiusDp) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(radiusDp));
    background.setStroke(dp(1), stroke);
    return background;
  }

  private LinearLayout.LayoutParams weight(float weight) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
    params.setMargins(dp(2), dp(2), dp(2), 0);
    return params;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  private String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
  }

  private void hostLog(String action, Throwable error) {
    Host owner = host;
    if (owner == null) return;
    try {
      owner.log(action, error.getClass().getSimpleName()
          + (error.getMessage() == null ? "" : ": " + error.getMessage()));
    } catch (Throwable ignored) {
      // Logging must never break the module.
    }
  }

  private void hostLog(String action, String detail) {
    Host owner = host;
    if (owner == null) return;
    try {
      owner.log(action, detail);
    } catch (Throwable ignored) {
      // Logging must never break the module.
    }
  }
}

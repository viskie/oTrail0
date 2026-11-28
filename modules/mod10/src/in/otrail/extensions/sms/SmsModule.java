package in.otrail.extensions.sms;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.modules.ModuleV1;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Car-friendly SMS reader and sender. Reads the system SMS provider and sends through
 * SmsManager; the module asks for its own runtime permissions on first use.
 */
public final class SmsModule implements ModuleV1 {
  private static final int BG = 0xff0b0e13, SURFACE = 0xff12161d, SURFACE_ALT = 0xff171c24,
      TEXT = 0xfff8fafc, MUTED = 0xff8494a3, CYAN = 0xff00d4ff, GREEN = 0xff55ff55,
      RED = 0xffff3838, AMBER = 0xffffb300, BORDER = 0xff2a3555, BUBBLE = 0xff1d2a3a;
  private static final int REQUEST = 9102;
  private static final String[] PERMISSIONS = {
      Manifest.permission.READ_SMS,
      Manifest.permission.SEND_SMS,
      Manifest.permission.READ_CONTACTS
  };

  private Activity activity;
  private Host host;
  private LinearLayout root;
  private FrameLayout container;
  private LinearLayout inboxScreen, threadScreen, composeScreen, inboxList, threadList,
      grantRow, tabRow;
  private TextView status, threadTitle;
  private LinearLayout inboxBody;
  private EditText search, toField, messageField, replyField;
  private Button inboxTab, composeTab;
  private ScrollView threadScroll;
  private String tab = "inbox";
  private String threadAddress = "";
  private long threadId = -1;
  private boolean loading;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<String, String> names = new HashMap<>();

  private static final class ThreadItem {
    final String address, snippet;
    final long date, threadId;
    final boolean unread;

    ThreadItem(String address, String snippet, long date, long threadId, boolean unread) {
      this.address = address == null ? "" : address;
      this.snippet = snippet == null ? "" : snippet;
      this.date = date;
      this.threadId = threadId;
      this.unread = unread;
    }
  }

  private static final class Message {
    final String address, body;
    final long date;
    final int type;

    Message(String address, String body, long date, int type) {
      this.address = address == null ? "" : address;
      this.body = body == null ? "" : body;
      this.date = date;
      this.type = type;
    }
  }

  @Override public String id() {
    return "mod10";
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
    inboxScreen = buildInbox();
    threadScreen = buildThread();
    composeScreen = buildCompose();
    container.addView(inboxScreen, new FrameLayout.LayoutParams(-1, -1));
    container.addView(threadScreen, new FrameLayout.LayoutParams(-1, -1));
    container.addView(composeScreen, new FrameLayout.LayoutParams(-1, -1));
    root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1f));
    selectTab("inbox");
    if (!missing().isEmpty()) showGrant(true);
    return root;
  }

  @Override public void resume() {
    if (activity == null) return;
    if (granted(Manifest.permission.READ_SMS)) {
      loadNames();
      if (tab.equals("inbox")) loadInbox();
      else if (tab.equals("thread")) loadThread();
    }
  }

  @Override public void permissionResult(int request, String[] permissions, int[] grants) {
    if (request != REQUEST) return;
    int denied = 0;
    for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) denied++;
    if (denied == 0) {
      showGrant(false);
      setStatus("SMS access granted.", GREEN);
      refreshCurrent();
    } else {
      showGrant(true);
      setStatus(denied + " permission(s) denied · tap ALLOW SMS ACCESS", RED);
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

  private void requestSmsPermissions() {
    if (activity == null) return;
    try {
      activity.requestPermissions(PERMISSIONS, REQUEST);
      setStatus("Allow messages, sending and contacts…", AMBER);
    } catch (Exception error) {
      hostLog("permission-failed", error);
      setStatus("This host build does not declare SMS permissions.", RED);
    }
  }

  // ---------------------------------------------------------------- screens

  private View header() {
    LinearLayout header = new LinearLayout(activity);
    header.setOrientation(LinearLayout.VERTICAL);

    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("SMS", 18, CYAN);
    title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    status = label("", 10, MUTED);
    status.setGravity(Gravity.END);
    status.setMaxLines(2);
    row.addView(status, new LinearLayout.LayoutParams(-2, -2));
    header.addView(row);

    tabRow = new LinearLayout(activity);
    tabRow.setPadding(0, dp(3), 0, dp(3));
    inboxTab = chip("INBOX", () -> selectTab("inbox"));
    composeTab = chip("NEW MESSAGE", () -> {
      toField.setText("");
      messageField.setText("");
      selectTab("compose");
    });
    tabRow.addView(inboxTab, weight(1));
    tabRow.addView(composeTab, weight(2));
    header.addView(tabRow);

    grantRow = new LinearLayout(activity);
    Button grant = chip("ALLOW SMS ACCESS", this::requestSmsPermissions);
    grant.setTextColor(0xff000000);
    grant.setBackground(panel(AMBER, AMBER, 8));
    grantRow.addView(grant, new LinearLayout.LayoutParams(-1, dp(36)));
    grantRow.setVisibility(View.GONE);
    header.addView(grantRow);
    return header;
  }

  private LinearLayout buildInbox() {
    LinearLayout screen = screen();
    search = new EditText(activity);
    search.setHint("Search messages or numbers");
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
        loadInbox();
      }
    });
    screen.addView(search, new LinearLayout.LayoutParams(-1, dp(44)));

    inboxBody = new LinearLayout(activity);
    inboxBody.setOrientation(LinearLayout.VERTICAL);
    setMessage(inboxBody, "Conversations appear here.");
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(inboxBody, new ScrollView.LayoutParams(-1, -2));
    screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    return screen;
  }

  private LinearLayout buildThread() {
    LinearLayout screen = screen();
    LinearLayout bar = new LinearLayout(activity);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.addView(chip("← INBOX", () -> selectTab("inbox")),
        new LinearLayout.LayoutParams(dp(88), dp(34)));
    threadTitle = label("", 13, CYAN);
    threadTitle.setSingleLine(true);
    bar.addView(threadTitle, new LinearLayout.LayoutParams(0, -2, 1));
    screen.addView(bar);

    threadList = new LinearLayout(activity);
    threadList.setOrientation(LinearLayout.VERTICAL);
    threadScroll = new ScrollView(activity);
    threadScroll.addView(threadList, new ScrollView.LayoutParams(-1, -2));
    screen.addView(threadScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    LinearLayout reply = new LinearLayout(activity);
    reply.setGravity(Gravity.CENTER_VERTICAL);
    replyField = new EditText(activity);
    replyField.setHint("Reply…");
    replyField.setHintTextColor(MUTED);
    replyField.setTextColor(TEXT);
    replyField.setTextSize(12);
    replyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    replyField.setBackground(panel(SURFACE_ALT, BORDER, 8));
    replyField.setPadding(dp(10), 0, dp(10), 0);
    reply.addView(replyField, new LinearLayout.LayoutParams(0, dp(46), 1));
    Button send = chip("SEND", this::sendReply);
    send.setTextColor(0xff001a00);
    send.setBackground(panel(GREEN, GREEN, 8));
    reply.addView(send, new LinearLayout.LayoutParams(dp(78), dp(46)));
    screen.addView(reply);
    return screen;
  }

  private LinearLayout buildCompose() {
    LinearLayout screen = screen();
    TextView title = label("NEW MESSAGE", 15, CYAN);
    title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    screen.addView(title);

    toField = new EditText(activity);
    toField.setHint("To (number)");
    toField.setHintTextColor(MUTED);
    toField.setTextColor(TEXT);
    toField.setTextSize(13);
    toField.setSingleLine(true);
    toField.setInputType(InputType.TYPE_CLASS_PHONE);
    toField.setBackground(panel(SURFACE_ALT, BORDER, 8));
    toField.setPadding(dp(10), 0, dp(10), 0);
    screen.addView(toField, new LinearLayout.LayoutParams(-1, dp(46)));

    messageField = new EditText(activity);
    messageField.setHint("Message");
    messageField.setHintTextColor(MUTED);
    messageField.setTextColor(TEXT);
    messageField.setTextSize(14);
    messageField.setGravity(Gravity.TOP);
    messageField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    messageField.setBackground(panel(SURFACE_ALT, BORDER, 8));
    messageField.setPadding(dp(10), dp(8), dp(10), dp(8));
    screen.addView(messageField, new LinearLayout.LayoutParams(-1, 0, 1f));

    Button send = chip("SEND MESSAGE", this::sendComposed);
    send.setTextSize(15);
    send.setTextColor(0xff001a00);
    send.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    send.setBackground(panel(GREEN, GREEN, 9));
    screen.addView(send, new LinearLayout.LayoutParams(-1, dp(54)));
    return screen;
  }

  private LinearLayout screen() {
    LinearLayout screen = new LinearLayout(activity);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setPadding(0, dp(4), 0, 0);
    return screen;
  }

  private void selectTab(String selected) {
    tab = selected;
    boolean inbox = selected.equals("inbox");
    boolean compose = selected.equals("compose");
    paintTab(inboxTab, inbox);
    paintTab(composeTab, compose);
    inboxScreen.setVisibility(inbox ? View.VISIBLE : View.GONE);
    threadScreen.setVisibility(selected.equals("thread") ? View.VISIBLE : View.GONE);
    composeScreen.setVisibility(compose ? View.VISIBLE : View.GONE);
    if (selected.equals("thread")) loadThread();
    else refreshCurrent();
  }

  private void paintTab(Button button, boolean active) {
    if (button == null) return;
    button.setTextColor(active ? 0xff000000 : TEXT);
    button.setBackground(panel(active ? CYAN : SURFACE_ALT, active ? CYAN : BORDER, 7));
  }

  private void refreshCurrent() {
    try {
      if (tab.equals("inbox")) loadInbox();
      else if (tab.equals("thread")) loadThread();
    } catch (RuntimeException error) {
      // Every module failure is forwarded to the LOGGER module (mod6).
      hostLog("refresh-failed", error);
      setStatus("SMS view failed: " + message(error), RED);
    }
  }

  // ---------------------------------------------------------------- data

  private void loadNames() {
    if (!granted(Manifest.permission.READ_CONTACTS) || activity == null) return;
    if (!names.isEmpty()) return;
    new Thread(() -> {
      Cursor cursor = null;
      try {
        cursor = activity.getContentResolver().query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER},
            null, null, null);
        if (cursor != null) while (cursor.moveToNext() && names.size() < 3000) {
          String name = cursor.getString(0);
          String phone = cursor.getString(1);
          if (name != null && phone != null) names.put(digits(phone), name);
        }
      } catch (Exception error) {
        hostLog("names-failed", error);
      } finally {
        if (cursor != null) cursor.close();
      }
      main.post(() -> {
        if (tab.equals("inbox")) loadInbox();
      });
    }, "sms-contacts").start();
  }

  private String display(String address) {
    String name = names.get(digits(address));
    return name == null ? address : name;
  }

  private void loadInbox() {
    if (activity == null || inboxBody == null) return;
    if (!granted(Manifest.permission.READ_SMS)) {
      setMessage(inboxBody, "Allow messages to read the inbox.\n\nNEW MESSAGE can send once SMS "
          + "permission is granted.");
      showGrant(true);
      return;
    }
    if (loading) return;
    loading = true;
    setMessage(inboxBody, "Reading messages…");
    final String filter = search == null ? ""
        : search.getText().toString().trim().toLowerCase(Locale.US);
    new Thread(() -> {
      Map<String, ThreadItem> threads = new LinkedHashMap<>();
      String problem = null;
      Cursor cursor = null;
      try {
        cursor = activity.getContentResolver().query(Telephony.Sms.CONTENT_URI,
            new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                Telephony.Sms.THREAD_ID, Telephony.Sms.READ},
            null, null, Telephony.Sms.DATE + " DESC");
        int read = 0;
        if (cursor != null) while (cursor.moveToNext() && read < 800) {
          read++;
          String address = cursor.getString(0);
          String body = cursor.getString(1);
          String name = display(address == null ? "" : address);
          if (!filter.isEmpty()
              && !(address != null && address.toLowerCase(Locale.US).contains(filter))
              && !name.toLowerCase(Locale.US).contains(filter)
              && !(body != null && body.toLowerCase(Locale.US).contains(filter))) continue;
          String key = address == null ? "" : digits(address);
          if (key.isEmpty()) key = address;
          if (threads.containsKey(key)) continue;
          threads.put(key, new ThreadItem(address, body, cursor.getLong(2), cursor.getLong(3),
              cursor.getInt(4) == 0));
        }
      } catch (Exception error) {
        problem = message(error);
        hostLog("inbox-failed", error);
      } finally {
        if (cursor != null) cursor.close();
      }
      final List<ThreadItem> items = new ArrayList<>(threads.values());
      final String failure = problem;
      main.post(() -> {
        loading = false;
        if (activity == null || inboxBody == null) return;
        if (failure != null) {
          setMessage(inboxBody, "Could not read messages: " + failure);
          return;
        }
        if (items.isEmpty()) {
          setMessage(inboxBody, filter.isEmpty() ? "No messages yet."
              : "Nothing matches \"" + filter + "\".");
          return;
        }
        inboxBody.removeAllViews();
        for (ThreadItem item : items) inboxBody.addView(threadRow(item));
        setStatus(items.size() + " conversation(s)", MUTED);
      });
    }, "sms-inbox").start();
  }

  private View threadRow(ThreadItem item) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), dp(8), dp(10), dp(8));
    row.setBackground(panel(SURFACE, BORDER, 8));

    LinearLayout text = new LinearLayout(activity);
    text.setOrientation(LinearLayout.VERTICAL);
    LinearLayout titleRow = new LinearLayout(activity);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView name = label(display(item.address), 14, item.unread ? CYAN : TEXT);
    name.setSingleLine(true);
    titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    titleRow.addView(label(shortTime(item.date), 9, MUTED));
    text.addView(titleRow);
    TextView snippet = label(item.snippet.replace('\n', ' '), 11, MUTED);
    snippet.setSingleLine(true);
    snippet.setEllipsize(TextUtils.TruncateAt.END);
    text.addView(snippet);
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

    row.setOnClickListener(view -> openThread(item.address, item.threadId));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(2), 0, dp(2));
    row.setLayoutParams(params);
    return row;
  }

  private void openThread(String address, long id) {
    threadAddress = address;
    threadId = id;
    threadTitle.setText(display(address));
    replyField.setText("");
    selectTab("thread");
  }

  private void loadThread() {
    if (activity == null || threadList == null) return;
    if (!granted(Manifest.permission.READ_SMS)) {
      threadList.removeAllViews();
      threadList.addView(label("Allow messages to read this conversation.", 11, MUTED));
      showGrant(true);
      return;
    }
    final String address = threadAddress;
    final long id = threadId;
    new Thread(() -> {
      List<Message> messages = new ArrayList<>();
      String problem = null;
      Cursor cursor = null;
      try {
        String selection;
        String[] arguments;
        if (id > 0) {
          selection = Telephony.Sms.THREAD_ID + "=?";
          arguments = new String[]{Long.toString(id)};
        } else {
          selection = Telephony.Sms.ADDRESS + "=?";
          arguments = new String[]{address};
        }
        cursor = activity.getContentResolver().query(Telephony.Sms.CONTENT_URI,
            new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                Telephony.Sms.TYPE}, selection, arguments, Telephony.Sms.DATE + " ASC");
        if (cursor != null) while (cursor.moveToNext()) {
          messages.add(new Message(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
              cursor.getInt(3)));
          if (messages.size() > 300) messages.remove(0);
        }
      } catch (Exception error) {
        problem = message(error);
        hostLog("thread-failed", error);
      } finally {
        if (cursor != null) cursor.close();
      }
      final List<Message> thread = messages;
      final String failure = problem;
      main.post(() -> {
        if (activity == null || threadList == null) return;
        threadList.removeAllViews();
        threadTitle.setText(display(address));
        if (failure != null) {
          threadList.addView(label("Could not read this conversation: " + failure, 11, RED));
          return;
        }
        if (thread.isEmpty()) {
          threadList.addView(label("No messages in this conversation.", 11, MUTED));
          return;
        }
        for (Message message : thread) threadList.addView(bubble(message));
        threadScroll.post(() -> threadScroll.fullScroll(View.FOCUS_DOWN));
      });
    }, "sms-thread").start();
  }

  private View bubble(Message message) {
    boolean sent = message.type == Telephony.Sms.MESSAGE_TYPE_SENT;
    LinearLayout wrap = new LinearLayout(activity);
    wrap.setGravity(sent ? Gravity.END : Gravity.START);
    wrap.setPadding(0, dp(2), 0, dp(2));

    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(10), dp(7), dp(10), dp(7));
    box.setBackground(panel(sent ? 0xff12362a : BUBBLE, sent ? GREEN : BORDER, 10));
    TextView body = label(message.body, 12, TEXT);
    body.setTextIsSelectable(true);
    box.addView(body);
    TextView stamp = label(shortTime(message.date), 8, MUTED);
    stamp.setGravity(sent ? Gravity.END : Gravity.START);
    box.addView(stamp);
    int maximum = Math.round(activity.getResources().getDisplayMetrics().widthPixels * .80f);
    wrap.addView(box, new LinearLayout.LayoutParams(maximum, -2));
    return wrap;
  }

  // ---------------------------------------------------------------- sending

  private void sendReply() {
    String body = replyField.getText().toString().trim();
    if (body.isEmpty()) {
      setStatus("Type a reply first.", AMBER);
      return;
    }
    if (send(threadAddress, body)) replyField.setText("");
  }

  private void sendComposed() {
    String to = toField.getText().toString().trim();
    String body = messageField.getText().toString().trim();
    if (to.isEmpty() || body.isEmpty()) {
      setStatus("A number and a message are required.", AMBER);
      return;
    }
    if (send(to, body)) {
      messageField.setText("");
      selectTab("inbox");
    }
  }

  private boolean send(String to, String body) {
    if (!granted(Manifest.permission.SEND_SMS)) {
      setStatus("Allow SMS to send messages.", RED);
      showGrant(true);
      return false;
    }
    try {
      SmsManager manager = SmsManager.getDefault();
      ArrayList<String> parts = manager.divideMessage(body);
      if (parts.size() > 1) manager.sendMultipartTextMessage(to, null, parts, null, null);
      else manager.sendTextMessage(to, null, body, null, null);
      setStatus("Sent to " + display(to), GREEN);
      hostLog("sms-sent", "to " + to + " · " + body.length() + " chars");
      main.postDelayed(() -> {
        if (tab.equals("thread")) loadThread();
        else if (tab.equals("inbox")) loadInbox();
      }, 800);
      return true;
    } catch (Exception error) {
      hostLog("sms-send-failed", error);
      setStatus("Send failed: " + message(error), RED);
      return false;
    }
  }

  // ---------------------------------------------------------------- widgets

  private void setMessage(LinearLayout list, String text) {
    if (list == null) return;
    list.removeAllViews();
    TextView message = label(text, 11, MUTED);
    message.setPadding(dp(4), dp(6), dp(4), dp(6));
    list.addView(message);
  }

  private String digits(String value) {
    if (value == null) return "";
    String digits = value.replaceAll("[^0-9]", "");
    return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
  }

  private String shortTime(long time) {
    long age = System.currentTimeMillis() - time;
    if (age < 86_400_000L) return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(time));
    if (age < 7L * 86_400_000L) return new SimpleDateFormat("EEE", Locale.US).format(new Date(time));
    return new SimpleDateFormat("dd/MM/yy", Locale.US).format(new Date(time));
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
    view.setPadding(0, dp(1), 0, dp(1));
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
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(34), weight);
    params.setMargins(dp(2), 0, dp(2), 0);
    return params;
  }

  private void setStatus(String text, int color) {
    if (status == null) return;
    status.setText(text);
    status.setTextColor(color);
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

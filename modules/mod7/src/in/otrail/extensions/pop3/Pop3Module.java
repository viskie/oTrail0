package in.otrail.extensions.pop3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.modules.ModuleV1;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-account POP3 client with an offline inbox. Messages are cached on the
 * phone and refreshed automatically whenever the module is opened.
 */
public final class Pop3Module implements ModuleV1 {
  private static final int BG = 0xff0b0e13, SURFACE = 0xff12161d, SURFACE_ALT = 0xff171c24,
      TEXT = 0xfff8fafc, MUTED = 0xff8494a3, CYAN = 0xff00d4ff, PURPLE = 0xff9b59ff,
      GREEN = 0xff55ff55, RED = 0xffff3838, BORDER = 0xff2a3555;

  private Activity activity;
  private Host host;
  private Pop3Store store;
  private FrameLayout container;
  private LinearLayout inboxScreen, detailScreen, accountsScreen, formScreen;
  private LinearLayout accountChips, messageList, accountsList, formRoot;
  private TextView status, detailSubject, detailMeta, detailBody, formStatus;
  private EditText fieldName, fieldHost, fieldPort, fieldUser, fieldPassword;
  private CheckBox fieldTls;
  private String selectedAccount;
  private Pop3Account editing;
  private Pop3Message opened;
  private volatile boolean syncing;
  private List<Pop3Message> shown = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final SimpleDateFormat stamp =
      new SimpleDateFormat("d MMM, HH:mm", Locale.US);

  @Override public String id() {
    return "mod7";
  }

  @Override public View create(Activity activity, Host host) throws Exception {
    this.activity = activity;
    this.host = host;
    store = new Pop3Store(activity, host.privateDirectory());

    container = new FrameLayout(activity);
    container.setBackgroundColor(BG);
    inboxScreen = buildInbox();
    detailScreen = buildDetail();
    accountsScreen = buildAccounts();
    formScreen = buildForm();
    container.addView(inboxScreen, new FrameLayout.LayoutParams(-1, -1));
    container.addView(detailScreen, new FrameLayout.LayoutParams(-1, -1));
    container.addView(accountsScreen, new FrameLayout.LayoutParams(-1, -1));
    container.addView(formScreen, new FrameLayout.LayoutParams(-1, -1));

    if (store.accounts().isEmpty()) {
      show(accountsScreen);
      rebuildAccounts();
      setStatus("Add a mailbox to start the offline inbox.", MUTED);
    } else {
      show(inboxScreen);
      refreshInbox();
      sync(false);
    }
    return container;
  }

  // ---------------------------------------------------------------- inbox screen

  private LinearLayout buildInbox() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(9), dp(7), dp(9), dp(7));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("POP3 CLIENT", 17, TEXT);
    title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(chip("SYNC", this::syncFromButton), new LinearLayout.LayoutParams(dp(62), dp(30)));
    header.addView(chip("ACCOUNTS", () -> {
      rebuildAccounts();
      show(accountsScreen);
    }), new LinearLayout.LayoutParams(dp(86), dp(30)));
    root.addView(header);

    HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
    chipScroll.setHorizontalScrollBarEnabled(false);
    accountChips = new LinearLayout(activity);
    accountChips.setOrientation(LinearLayout.HORIZONTAL);
    chipScroll.addView(accountChips, new HorizontalScrollView.LayoutParams(-2, -2));
    root.addView(chipScroll);

    status = label("", 10, MUTED);
    status.setPadding(dp(2), dp(4), dp(2), dp(4));
    root.addView(status);

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    messageList = new LinearLayout(activity);
    messageList.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(messageList, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    return root;
  }

  private void refreshInbox() {
    if (activity == null || messageList == null) return;
    List<Pop3Account> accounts = store.accounts();
    if (accounts.isEmpty()) {
      shown = new ArrayList<>();
      messageList.removeAllViews();
      messageList.addView(note("No mailboxes configured yet."));
      rebuildAccountChips(accounts);
      return;
    }
    if (selectedAccount != null && findAccount(accounts, selectedAccount) == null)
      selectedAccount = null;
    List<Pop3Message> all = new ArrayList<>();
    long last = 0;
    for (Pop3Account account : accounts) {
      if (selectedAccount != null && !selectedAccount.equals(account.id)) continue;
      all.addAll(store.messages(account.id));
      last = Math.max(last, store.lastSync(account.id));
    }
    all.sort((a, b) -> {
      long left = a.time > 0 ? a.time : 0, right = b.time > 0 ? b.time : 0;
      if (left != right) return Long.compare(right, left);
      return b.uid.compareTo(a.uid);
    });
    shown = all;
    rebuildAccountChips(accounts);
    messageList.removeAllViews();

    String scope = selectedAccount == null ? "all mailboxes" : findAccount(accounts, selectedAccount).label();
    if (all.isEmpty()) {
      messageList.addView(note("Offline inbox is empty for " + scope
          + ". Tap SYNC to download messages."));
    } else {
      for (Pop3Message message : all) messageList.addView(messageRow(message, accounts));
    }
    String sync = last > 0 ? "last sync " + stamp.format(new Date(last)) : "never synced";
    setStatus(all.size() + " cached · " + scope + " · " + sync
        + (syncing ? " · syncing…" : ""), syncing ? CYAN : MUTED);
  }

  private void rebuildAccountChips(List<Pop3Account> accounts) {
    if (accountChips == null) return;
    accountChips.removeAllViews();
    accountChips.addView(accountChip("ALL", selectedAccount == null, () -> {
      selectedAccount = null;
      refreshInbox();
    }));
    for (Pop3Account account : accounts) {
      accountChips.addView(accountChip(account.label(), account.id.equals(selectedAccount), () -> {
        selectedAccount = account.id;
        refreshInbox();
      }));
    }
  }

  private View accountChip(String text, boolean active, Runnable action) {
    TextView chip = new TextView(activity);
    chip.setText(text);
    chip.setTextSize(10);
    chip.setSingleLine(true);
    chip.setEllipsize(TextUtils.TruncateAt.END);
    chip.setTypeface(Typeface.DEFAULT_BOLD);
    chip.setGravity(Gravity.CENTER);
    chip.setPadding(dp(12), dp(6), dp(12), dp(6));
    chip.setTextColor(active ? 0xff000000 : CYAN);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(active ? CYAN : SURFACE_ALT);
    frame.setCornerRadius(dp(14));
    frame.setStroke(dp(1), active ? CYAN : BORDER);
    chip.setBackground(frame);
    chip.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
    p.setMargins(dp(2), dp(3), dp(3), dp(3));
    chip.setLayoutParams(p);
    return chip;
  }

  private View messageRow(Pop3Message message, List<Pop3Account> accounts) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(8), dp(7), dp(8), dp(7));
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(SURFACE);
    frame.setCornerRadius(dp(8));
    frame.setStroke(dp(1), message.seen ? BORDER : CYAN);
    row.setBackground(frame);

    LinearLayout top = new LinearLayout(activity);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView subject = label(message.displaySubject(), 13, message.seen ? TEXT : CYAN);
    subject.setSingleLine(true);
    subject.setEllipsize(TextUtils.TruncateAt.END);
    subject.setTypeface(Typeface.DEFAULT_BOLD);
    top.addView(subject, new LinearLayout.LayoutParams(0, -2, 1));
    if (!message.seen) {
      TextView dot = label("●", 10, CYAN);
      dot.setPadding(dp(4), 0, 0, 0);
      top.addView(dot);
    }
    row.addView(top);

    Pop3Account account = findAccount(accounts, message.accountId);
    String when = message.time > 0 ? stamp.format(new Date(message.time)) : message.date;
    TextView meta = label(shorten(message.from, 42) + " · " + when
        + (account == null ? "" : " · " + account.label()), 9, MUTED);
    meta.setSingleLine(true);
    meta.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(meta);

    TextView body = label(message.snippet(), 10, MUTED);
    body.setMaxLines(2);
    body.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(body);

    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(3), 0, dp(3));
    row.setLayoutParams(p);
    row.setOnClickListener(v -> openMessage(message));
    return row;
  }

  // ---------------------------------------------------------------- detail screen

  private LinearLayout buildDetail() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(9), dp(7), dp(9), dp(7));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("MESSAGE", 17, TEXT);
    title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(chip("BACK", this::backToInbox), new LinearLayout.LayoutParams(dp(58), dp(30)));
    root.addView(header);

    detailSubject = label("", 15, CYAN);
    detailSubject.setTypeface(Typeface.DEFAULT_BOLD);
    detailSubject.setPadding(0, dp(6), 0, dp(2));
    root.addView(detailSubject);
    detailMeta = label("", 10, MUTED);
    detailMeta.setPadding(0, 0, 0, dp(6));
    root.addView(detailMeta);

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    detailBody = label("", 12, TEXT);
    detailBody.setTextIsSelectable(true);
    detailBody.setLineSpacing(dp(2), 1f);
    scroll.addView(detailBody, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout actions = new LinearLayout(activity);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.addView(chip("DELETE FROM SERVER", this::deleteOpened), new LinearLayout.LayoutParams(0, dp(30), 1));
    actions.addView(chip("KEEP OFFLINE", this::backToInbox), new LinearLayout.LayoutParams(0, dp(30), 1));
    root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    return root;
  }

  private void openMessage(Pop3Message message) {
    opened = message;
    message.seen = true;
    Pop3Account account = findAccount(store.accounts(), message.accountId);
    saveMessage(message);
    detailSubject.setText(message.displaySubject());
    detailMeta.setText(shorten(message.from, 64) + "\n"
        + (message.time > 0 ? stamp.format(new Date(message.time)) : message.date)
        + (account == null ? "" : " · " + account.label())
        + (message.size > 0 ? " · " + (message.size / 1024) + " KB" : ""));
    detailBody.setText(message.body == null || message.body.trim().isEmpty()
        ? "(empty message body)" : message.body);
    show(detailScreen);
  }

  private void saveMessage(Pop3Message message) {
    List<Pop3Message> cached = store.messages(message.accountId);
    boolean replaced = false;
    for (int i = 0; i < cached.size(); i++) {
      if (cached.get(i).uid.equals(message.uid)) {
        cached.set(i, message);
        replaced = true;
        break;
      }
    }
    if (!replaced) cached.add(message);
    store.saveMessages(message.accountId, cached);
  }

  private void deleteOpened() {
    final Pop3Message message = opened;
    if (message == null) return;
    new AlertDialog.Builder(activity)
        .setTitle("Delete from server?")
        .setMessage("The cached copy is removed immediately. The server copy is deleted on the next successful connection.")
        .setPositiveButton("DELETE", (dialog, which) -> deleteFromServer(message))
        .setNegativeButton("CANCEL", null)
        .show();
  }

  private void deleteFromServer(Pop3Message message) {
    setStatus("Deleting from server…", CYAN);
    new Thread(() -> {
      String error = null;
      try {
        Pop3Account account = findAccount(store.accounts(), message.accountId);
        if (account == null) throw new Exception("Mailbox no longer configured");
        try (Pop3Client client = new Pop3Client(account)) {
          boolean found = false;
          for (Pop3Client.Entry entry : client.entries()) {
            if (entry.uid.equals(message.uid)) {
              client.delete(entry.number);
              found = true;
              break;
            }
          }
          if (!found) error = "Message is no longer on the server; removing the offline copy.";
        }
      } catch (Exception failure) {
        logError("delete-failed", failure);
        error = failureMessage(failure);
      }
      final String result = error;
      main.post(() -> {
        if (activity == null) return;
        List<Pop3Message> cached = store.messages(message.accountId);
        for (int i = cached.size() - 1; i >= 0; i--)
          if (cached.get(i).uid.equals(message.uid)) cached.remove(i);
        store.saveMessages(message.accountId, cached);
        backToInbox();
        if (result != null && !result.startsWith("Message is no longer")) setStatus(result, RED);
        else setStatus("Removed from the offline inbox.", MUTED);
      });
    }, "pop3-delete").start();
  }

  private void backToInbox() {
    opened = null;
    refreshInbox();
    show(inboxScreen);
  }

  // ---------------------------------------------------------------- accounts screen

  private LinearLayout buildAccounts() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(9), dp(7), dp(9), dp(7));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("MAILBOXES", 17, TEXT);
    title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(chip("BACK", () -> {
      refreshInbox();
      show(inboxScreen);
    }), new LinearLayout.LayoutParams(dp(58), dp(30)));
    header.addView(chip("+ ADD", () -> {
      editing = null;
      openForm();
    }), new LinearLayout.LayoutParams(dp(64), dp(30)));
    root.addView(header);

    TextView hint = label("Each mailbox keeps its own offline inbox. Sync runs when this module opens.",
        10, MUTED);
    hint.setPadding(0, dp(4), 0, dp(6));
    root.addView(hint);

    ScrollView scroll = new ScrollView(activity);
    accountsList = new LinearLayout(activity);
    accountsList.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(accountsList, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    return root;
  }

  private void rebuildAccounts() {
    if (accountsList == null) return;
    List<Pop3Account> accounts = store.accounts();
    accountsList.removeAllViews();
    if (accounts.isEmpty()) {
      accountsList.addView(note("No mailboxes yet. Tap + ADD to configure server, user and password."));
      return;
    }
    for (Pop3Account account : accounts) accountsList.addView(accountRow(account));
  }

  private View accountRow(Pop3Account account) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(8), dp(7), dp(8), dp(7));
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(SURFACE);
    frame.setCornerRadius(dp(8));
    frame.setStroke(dp(1), BORDER);
    row.setBackground(frame);

    TextView name = label(account.label(), 13, CYAN);
    name.setTypeface(Typeface.DEFAULT_BOLD);
    row.addView(name);
    row.addView(label(account.endpoint(), 10, MUTED));
    long last = store.lastSync(account.id);
    int cached = store.messages(account.id).size();
    row.addView(label(cached + " cached · "
        + (last > 0 ? "synced " + stamp.format(new Date(last)) : "never synced"), 9, MUTED));

    LinearLayout actions = new LinearLayout(activity);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.addView(chip("SYNC", () -> {
      selectedAccount = account.id;
      show(inboxScreen);
      sync(true);
    }), new LinearLayout.LayoutParams(0, dp(28), 1));
    actions.addView(chip("EDIT", () -> {
      editing = account;
      openForm();
    }), new LinearLayout.LayoutParams(0, dp(28), 1));
    actions.addView(chip("DELETE", () -> confirmDelete(account)), new LinearLayout.LayoutParams(0, dp(28), 1));
    row.addView(actions);

    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(3), 0, dp(3));
    row.setLayoutParams(p);
    return row;
  }

  private void confirmDelete(Pop3Account account) {
    new AlertDialog.Builder(activity)
        .setTitle("Remove mailbox?")
        .setMessage("The account settings and its " + store.messages(account.id).size()
            + " cached messages are deleted from this phone. Server mail is untouched.")
        .setPositiveButton("REMOVE", (dialog, which) -> {
          store.deleteAccount(account.id);
          if (account.id.equals(selectedAccount)) selectedAccount = null;
          rebuildAccounts();
          refreshInbox();
          setStatus("Removed " + account.label() + ".", MUTED);
        })
        .setNegativeButton("CANCEL", null)
        .show();
  }

  // ---------------------------------------------------------------- account form

  private LinearLayout buildForm() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(9), dp(7), dp(9), dp(7));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("MAILBOX SETUP", 17, TEXT);
    title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(chip("CANCEL", () -> {
      rebuildAccounts();
      show(accountsScreen);
    }), new LinearLayout.LayoutParams(dp(70), dp(30)));
    root.addView(header);

    formStatus = label("", 10, MUTED);
    formStatus.setPadding(0, dp(4), 0, dp(4));
    root.addView(formStatus);

    ScrollView scroll = new ScrollView(activity);
    formRoot = new LinearLayout(activity);
    formRoot.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(formRoot, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    fieldName = field("Label (optional)");
    fieldHost = field("Server, e.g. pop.gmail.com");
    fieldPort = field("Port");
    fieldPort.setInputType(InputType.TYPE_CLASS_NUMBER);
    fieldUser = field("Username or address");
    fieldUser.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    fieldPassword = field("Password");
    fieldPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    fieldTls = new CheckBox(activity);
    fieldTls.setText("Use TLS (recommended)");
    fieldTls.setTextColor(TEXT);
    fieldTls.setTextSize(11);
    fieldTls.setButtonTintList(android.content.res.ColorStateList.valueOf(CYAN));
    formRoot.addView(fieldTls);

    Button save = chip("SAVE & SYNC", this::saveForm);
    save.setTextColor(0xff000000);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(CYAN);
    frame.setCornerRadius(dp(7));
    frame.setStroke(dp(1), CYAN);
    save.setBackground(frame);
    LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(34));
    saveParams.setMargins(0, dp(8), 0, dp(4));
    formRoot.addView(save, saveParams);
    formRoot.addView(note("Credentials stay in this app's private storage and are only sent to the "
        + "configured POP3 server."));
    return root;
  }

  private void openForm() {
    Pop3Account account = editing;
    fieldName.setText(account == null ? "" : account.name);
    fieldHost.setText(account == null ? "" : account.host);
    fieldPort.setText(account == null ? "995" : Integer.toString(account.port));
    fieldUser.setText(account == null ? "" : account.user);
    fieldPassword.setText(account == null ? "" : account.password);
    fieldTls.setChecked(account == null || account.tls);
    formStatus.setText(account == null ? "New mailbox" : "Editing " + account.label());
    formStatus.setTextColor(MUTED);
    show(formScreen);
  }

  private void saveForm() {
    String host = fieldHost.getText().toString().trim();
    String user = fieldUser.getText().toString().trim();
    if (host.isEmpty() || user.isEmpty()) {
      formStatus.setText("Server and username are required.");
      formStatus.setTextColor(RED);
      return;
    }
    int port = 995;
    try {
      port = Integer.parseInt(fieldPort.getText().toString().trim());
    } catch (NumberFormatException ignored) {
      // Keep the default port.
    }
    Pop3Account account = editing == null ? new Pop3Account() : editing;
    if (account.id.isEmpty()) account.id = Pop3Store.newId();
    account.name = fieldName.getText().toString().trim();
    account.host = host;
    account.port = port;
    account.user = user;
    account.password = fieldPassword.getText().toString();
    account.tls = fieldTls.isChecked();

    List<Pop3Account> accounts = store.accounts();
    boolean replaced = false;
    for (int i = 0; i < accounts.size(); i++) {
      if (accounts.get(i).id.equals(account.id)) {
        accounts.set(i, account);
        replaced = true;
        break;
      }
    }
    if (!replaced) accounts.add(account);
    store.saveAccounts(accounts);
    selectedAccount = account.id;
    editing = null;
    refreshInbox();
    show(inboxScreen);
    sync(true);
  }

  // ---------------------------------------------------------------- sync

  private void syncFromButton() {
    if (syncing) return;
    sync(false);
  }

  private void sync(boolean onlySelected) {
    if (syncing) return;
    List<Pop3Account> accounts = store.accounts();
    if (accounts.isEmpty()) {
      rebuildAccounts();
      show(accountsScreen);
      return;
    }
    final List<Pop3Account> targets = new ArrayList<>();
    for (Pop3Account account : accounts)
      if (!onlySelected || selectedAccount == null || selectedAccount.equals(account.id))
        targets.add(account);
    syncing = true;
    if (status != null) setStatus("Syncing " + targets.size() + " mailbox"
        + (targets.size() == 1 ? "" : "es") + "…", CYAN);
    host.keepProcessAlive(true);
    new Thread(() -> {
      int fetched = 0;
      StringBuilder errors = new StringBuilder();
      for (Pop3Account account : targets) {
        try {
          fetched += syncAccount(account);
        } catch (Exception failure) {
          logError("sync-failed", failure);
          if (errors.length() > 0) errors.append('\n');
          errors.append(account.label()).append(": ").append(failureMessage(failure));
        }
      }
      final int count = fetched;
      final String failure = errors.toString().trim();
      main.post(() -> {
        syncing = false;
        if (activity == null) return;
        host.keepProcessAlive(false);
        refreshInbox();
        if (!failure.isEmpty()) {
          setStatus(failure, RED);
        } else if (count > 0) {
          setStatus("Synced " + count + " new message" + (count == 1 ? "" : "s")
              + " · " + shown.size() + " cached.", GREEN);
        }
      });
    }, "pop3-sync").start();
  }

  /** Fetches only messages missing from the offline inbox, newest first. */
  private int syncAccount(Pop3Account account) throws Exception {
    Map<String, Pop3Message> cached = new LinkedHashMap<>();
    for (Pop3Message message : store.messages(account.id)) {
      message.accountId = account.id;
      cached.put(message.uid, message);
    }
    int fetched = 0;
    try (Pop3Client client = new Pop3Client(account)) {
      List<Pop3Client.Entry> entries = client.entries();
      List<Pop3Client.Entry> missing = new ArrayList<>();
      for (Pop3Client.Entry entry : entries)
        if (!cached.containsKey(entry.uid)) missing.add(entry);
      int budget = 40;
      for (int i = missing.size() - 1; i >= 0 && budget > 0; i--, budget--) {
        Pop3Client.Entry entry = missing.get(i);
        Pop3Message message = Mime.parse(client.retrieve(entry.number));
        message.accountId = account.id;
        message.uid = entry.uid;
        message.size = entry.size;
        cached.put(message.uid, message);
        fetched++;
      }
      for (Pop3Client.Entry entry : entries) {
        Pop3Message message = cached.get(entry.uid);
        if (message != null) message.size = entry.size;
      }
    }
    store.saveMessages(account.id, new ArrayList<>(cached.values()));
    store.setLastSync(account.id, System.currentTimeMillis());
    return fetched;
  }

  // ---------------------------------------------------------------- helpers

  private void setStatus(String text, int color) {
    if (status == null) return;
    status.setText(text);
    status.setTextColor(color);
  }

  private Pop3Account findAccount(List<Pop3Account> accounts, String id) {
    if (id == null) return null;
    for (Pop3Account account : accounts) if (id.equals(account.id)) return account;
    return null;
  }

  private void show(View screen) {
    inboxScreen.setVisibility(screen == inboxScreen ? View.VISIBLE : View.GONE);
    detailScreen.setVisibility(screen == detailScreen ? View.VISIBLE : View.GONE);
    accountsScreen.setVisibility(screen == accountsScreen ? View.VISIBLE : View.GONE);
    formScreen.setVisibility(screen == formScreen ? View.VISIBLE : View.GONE);
  }

  private String failureMessage(Exception error) {
    String message = error.getMessage();
    if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
    return message;
  }

  /** Report failures to the LOGGER module through the host event log. */
  private void logError(String action, Throwable error) {
    Host owner = host;
    if (owner == null) return;
    try {
      owner.log(action, failureMessage(
          error instanceof Exception ? (Exception) error : new Exception(error)));
    } catch (Throwable ignored) {
      // Logging must never break the module.
    }
  }

  private static String shorten(String value, int max) {
    if (value == null) return "";
    String text = value.trim();
    return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
  }

  private TextView note(String text) {
    TextView view = label(text, 11, MUTED);
    view.setPadding(dp(6), dp(10), dp(6), dp(10));
    return view;
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setPadding(dp(2), dp(2), dp(2), dp(2));
    return view;
  }

  private Button chip(String text, Runnable action) {
    Button button = new Button(activity);
    button.setBackgroundTintList(null);
    button.setStateListAnimator(null);
    button.setText(text);
    button.setTextSize(9);
    button.setTextColor(CYAN);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(4), 0, dp(4), 0);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(0xff000000);
    frame.setCornerRadius(dp(7));
    frame.setStroke(dp(1), BORDER);
    button.setBackground(frame);
    button.setOnClickListener(v -> {
      try {
        action.run();
      } catch (Throwable error) {
        logError("action-failed", error);
        setStatus("Action failed: " + failureMessage(
            error instanceof Exception ? (Exception) error : new Exception(error)), RED);
      }
    });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(30));
    p.setMargins(dp(2), 0, dp(2), 0);
    button.setLayoutParams(p);
    return button;
  }

  private EditText field(String hint) {
    TextView caption = label(hint, 10, MUTED);
    caption.setPadding(0, dp(7), 0, dp(2));
    formRoot.addView(caption);
    EditText input = new EditText(activity);
    input.setHint(hint);
    input.setHintTextColor(0xff5f6f80);
    input.setTextColor(TEXT);
    input.setTextSize(12);
    input.setSingleLine(true);
    input.setImeOptions(EditorInfo.IME_ACTION_NEXT);
    input.setPadding(dp(9), dp(7), dp(9), dp(7));
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(SURFACE_ALT);
    frame.setCornerRadius(dp(7));
    frame.setStroke(dp(1), BORDER);
    input.setBackground(frame);
    formRoot.addView(input, new LinearLayout.LayoutParams(-1, dp(40)));
    return input;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  @Override public void resume() {
    if (activity != null && store != null) refreshInbox();
  }

  @Override public void pause() {}

  @Override public void close() {
    activity = null;
    host = null;
    store = null;
    container = null;
    inboxScreen = null;
    detailScreen = null;
    accountsScreen = null;
    formScreen = null;
  }
}

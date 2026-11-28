package in.otrail.extensions.opay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.extensions.opay.core.BackupCrypto;
import in.otrail.extensions.opay.core.LedgerTransaction;
import in.otrail.extensions.opay.core.Money;
import in.otrail.extensions.opay.core.Reconciliation;
import in.otrail.extensions.opay.core.Reports;
import in.otrail.extensions.opay.core.StatementImport;
import in.otrail.extensions.opay.core.Upi;
import in.otrail.modules.ModuleV1;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * oPay: offline-first UPI payment hand-off, QR, ledger, statement import,
 * reconciliation and reporting. The module never sees a UPI PIN/OTP, never calls
 * provider-internal activities and never claims settlement from an Android result alone.
 */
public final class OpayModule implements ModuleV1 {
  static final int REQ_PAY = 9201, REQ_QR_GALLERY = 9202, REQ_IMPORT = 9203,
      REQ_EXPORT = 9204, REQ_BACKUP = 9205, REQ_RESTORE = 9206,
      REQ_PERMISSION = 9207, REQ_CAMERA = 9208, REQ_LOCATION = 9209;

  private Activity activity;
  private Host host;
  private OpayStore store;
  private ExecutorService worker;
  private final Handler main = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;

  private FrameLayout root;
  private LinearLayout nav;
  private ScrollView scroll;
  private LinearLayout body;
  private FrameLayout overlay;
  private LinearLayout statusRow;
  private TextView status;
  private Button lockButton;
  private final Map<String, Button> navButtons = new LinkedHashMap<>();

  private String screen = "home";
  private boolean locked;
  private QrScannerView scanner;

  private Upi.Request draft = new Upi.Request();
  private String providerPackage = "";
  private String providerName = "Generic UPI";
  private EditText payUpi, payName, payAmount, payNote;

  private EditText receiveUpi, receiveName, receiveAmount, receiveNote;
  private ImageView receiveQr;
  private Bitmap lastQr;

  private EditText ledgerSearch;
  private LinearLayout ledgerList, homeRecent;
  private TextView homeToday, homeMonth, homeUnreconciled;
  private LinearLayout homeBanner;
  private String ledgerFilter = "ALL";
  private int ledgerOffset;
  private final List<LedgerTransaction> ledgerRows = new ArrayList<>();
  private Button loadMore;
  private final Handler searchDelay = new Handler(Looper.getMainLooper());
  private Runnable pendingSearch;

  private StatementImport.Result importResult;
  private List<LedgerTransaction> importRows = new ArrayList<>();
  private String importFileName = "";

  private byte[] exportPayload;
  private String exportMime = "text/csv", exportName = "opay-ledger.csv";

  private String reportKind = "MONTH";
  private Reports.Range reportRange;
  private Reports.Summary reportSummary;

  private String pendingTxnId = "";

  @Override public String id() {
    return "mod12";
  }

  // ------------------------------------------------------------------ lifecycle

  @Override public View create(Activity activity, Host host) throws Exception {
    this.activity = activity;
    this.host = host;
    try {
      store = new OpayStore(host.privateDirectory());
    } catch (RuntimeException error) {
      hostLog("store-open-failed", error);
      throw error;
    }
    worker = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "opay-worker");
      thread.setPriority(Thread.NORM_PRIORITY - 1);
      return thread;
    });
    prefs = activity.getSharedPreferences("opay", Context.MODE_PRIVATE);
    try {
      activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } catch (RuntimeException ignored) {
      // Screenshot protection is best-effort on unusual window types.
    }
    root = new FrameLayout(activity);
    root.setBackgroundColor(Ui.BG);
    LinearLayout frame = Ui.column(activity);
    frame.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 5), Ui.dp(activity, 8), Ui.dp(activity, 4));
    frame.addView(header(), new LinearLayout.LayoutParams(-1, -2));
    frame.addView(navigation(), new LinearLayout.LayoutParams(-1, Ui.dp(activity, 36)));

    FrameLayout hostArea = new FrameLayout(activity);
    scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    body = Ui.column(activity);
    body.setPadding(0, Ui.dp(activity, 3), 0, Ui.dp(activity, 10));
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    hostArea.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    overlay = new FrameLayout(activity);
    overlay.setVisibility(View.GONE);
    hostArea.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    frame.addView(hostArea, new LinearLayout.LayoutParams(-1, 0, 1f));

    statusRow = Ui.row(activity);
    statusRow.setPadding(Ui.dp(activity, 2), Ui.dp(activity, 2), Ui.dp(activity, 2), 0);
    status = Ui.label(activity, "Offline-only ledger \u00b7 no cloud sync", 9.5f, Ui.MUTED);
    statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
    frame.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));
    root.addView(frame, new FrameLayout.LayoutParams(-1, -1));

    if (prefs.getBoolean("lock", false) && Biometrics.available(activity)) {
      locked = true;
      showLock();
    } else {
      show("home");
    }
    return root;
  }

  @Override public void resume() {
    if (activity == null) return;
    if (!locked && screen.equals("home")) refreshHome();
  }

  @Override public void pause() {
    if (scanner != null) scanner.stop();
  }

  @Override public boolean backPressed() {
    if (overlay.getVisibility() == View.VISIBLE) {
      closeOverlay();
      return true;
    }
    if (!screen.equals("home")) {
      show("home");
      return true;
    }
    return false;
  }

  @Override public void close() {
    if (scanner != null) {
      scanner.stop();
      scanner = null;
    }
    try {
      activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } catch (RuntimeException ignored) {
      // Window already detached.
    }
    if (worker != null) {
      worker.submit(() -> { if (store != null) store.close(); });
      worker.shutdown();
    } else if (store != null) {
      store.close();
    }
    activity = null;
    host = null;
  }

  // ------------------------------------------------------------------ chrome

  private View header() {
    LinearLayout header = Ui.row(activity);
    LinearLayout titles = Ui.column(activity);
    TextView title = Ui.label(activity, "oPay", 21, Ui.CYAN);
    title.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
        android.graphics.Typeface.BOLD));
    titles.addView(title);
    titles.addView(Ui.label(activity, "UPI \u00b7 ledger \u00b7 reconciliation \u00b7 offline",
        9.5f, Ui.MUTED));
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
    lockButton = Ui.button(activity, prefs.getBoolean("lock", false) ? "\u25a0 LOCK ON" : "\u25a1 LOCK",
        this::toggleLock);
    lockButton.setTextSize(8.5f);
    header.addView(lockButton, Ui.wrap());
    return header;
  }

  private View navigation() {
    HorizontalScrollView strip = new HorizontalScrollView(activity);
    strip.setHorizontalScrollBarEnabled(false);
    nav = Ui.row(activity);
    String[][] entries = {
        {"home", "HOME"}, {"pay", "PAY"}, {"receive", "RECEIVE"}, {"ledger", "LEDGER"},
        {"import", "IMPORT"}, {"reconcile", "RECONCILE"}, {"reports", "REPORTS"},
        {"settings", "MORE"}
    };
    for (String[] entry : entries) {
      Button chip = Ui.button(activity, entry[1], () -> show(entry[0]));
      chip.setTextSize(8.5f);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -1);
      params.setMargins(Ui.dp(activity, 1), Ui.dp(activity, 4), Ui.dp(activity, 1), Ui.dp(activity, 2));
      nav.addView(chip, params);
      navButtons.put(entry[0], chip);
    }
    strip.addView(nav, new HorizontalScrollView.LayoutParams(-2, -1));
    return strip;
  }

  private void show(String key) {
    if (locked) return;
    closeOverlay();
    screen = key;
    for (Map.Entry<String, Button> entry : navButtons.entrySet())
      Ui.paintChip(entry.getValue(), entry.getKey().equals(parentScreen(key)));
    body.removeAllViews();
    scroll.scrollTo(0, 0);
    View view;
    switch (key) {
      case "pay": view = buildPay(); break;
      case "receive": view = buildReceive(); break;
      case "ledger": view = buildLedger(); break;
      case "beneficiaries": view = buildBeneficiaries(); break;
      case "accounts": view = buildAccounts(); break;
      case "import": view = buildImport(); break;
      case "reconcile": view = buildReconcile(); break;
      case "reports": view = buildReports(); break;
      case "settings": view = buildSettings(); break;
      case "detail": view = lastDetail == null ? buildHome() : buildDetail(lastDetail); break;
      default: view = buildHome();
    }
    body.addView(view, new LinearLayout.LayoutParams(-1, -2));
  }

  private String parentScreen(String key) {
    if (key.equals("detail")) return "ledger";
    if (key.equals("beneficiaries") || key.equals("accounts")) return "settings";
    return key;
  }

  private void setStatus(String text, int color) {
    if (status == null) return;
    status.setText(text);
    status.setTextColor(color);
  }

  private void section(LinearLayout parent, String title) {
    TextView heading = Ui.label(activity, title, 10, Ui.CYAN);
    heading.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
        android.graphics.Typeface.BOLD));
    heading.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 3));
    parent.addView(heading);
  }

  // ------------------------------------------------------------------ home

  private View buildHome() {
    LinearLayout page = Ui.column(activity);
    homeBanner = Ui.column(activity);
    page.addView(homeBanner, new LinearLayout.LayoutParams(-1, -2));
    refreshPendingBanner();

    LinearLayout cards = Ui.row(activity);
    LinearLayout today = Ui.card(activity);
    today.addView(Ui.label(activity, "TODAY", 8.5f, Ui.MUTED));
    homeToday = Ui.label(activity, "\u2014", 15, Ui.TEXT);
    homeToday.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
        android.graphics.Typeface.BOLD));
    today.addView(homeToday);
    cards.addView(today, new LinearLayout.LayoutParams(0, -2, 1f));
    LinearLayout month = Ui.card(activity);
    month.addView(Ui.label(activity, "THIS MONTH", 8.5f, Ui.MUTED));
    homeMonth = Ui.label(activity, "\u2014", 15, Ui.TEXT);
    homeMonth.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
        android.graphics.Typeface.BOLD));
    month.addView(homeMonth);
    cards.addView(month, new LinearLayout.LayoutParams(0, -2, 1f));
    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
    cardParams.setMargins(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));
    page.addView(cards, cardParams);

    LinearLayout actions = Ui.row(activity);
    actions.addView(Ui.accent(activity, "SCAN QR", this::openScanner),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 38), 1f));
    actions.addView(Ui.primary(activity, "PAY", () -> show("pay")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 38), 1f));
    actions.addView(Ui.button(activity, "IMPORT", () -> show("import")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 38), 1f));
    page.addView(actions);

    homeUnreconciled = Ui.label(activity, "", 9.5f, Ui.AMBER);
    homeUnreconciled.setOnClickListener(view -> show("reconcile"));
    page.addView(homeUnreconciled);

    section(page, "RECENT");
    homeRecent = Ui.column(activity);
    page.addView(homeRecent, new LinearLayout.LayoutParams(-1, -2));
    refreshHome();
    return page;
  }

  private void refreshHome() {
    if (store == null) return;
    work("dashboard-failed", () -> {
      Reports.Summary todaySummary = store.summary(Reports.day(System.currentTimeMillis()), false);
      Reports.Summary monthSummary = store.summary(Reports.month(System.currentTimeMillis()), false);
      List<LedgerTransaction> recent = store.recent(6);
      List<LedgerTransaction> pending = new ArrayList<>();
      for (LedgerTransaction row : store.recent(30))
        if (row.execution == LedgerTransaction.Execution.INITIATED
            && System.currentTimeMillis() - row.createdAt < 24L * 3_600_000L) pending.add(row);
      ui(() -> {
        if (homeToday == null) return;
        homeToday.setText(Money.format(todaySummary.debits) + " out\n"
            + Money.format(todaySummary.credits) + " in");
        homeMonth.setText(Money.format(monthSummary.debits) + " out\n"
            + Money.signed(monthSummary.net) + " net");
        homeUnreconciled.setText(monthSummary.unreconciled + " unreconciled \u00b7 "
            + monthSummary.pending + " pending \u00b7 tap to review");
        if (homeRecent != null) {
          homeRecent.removeAllViews();
          if (recent.isEmpty()) {
            Ui.message(homeRecent, activity, "No transactions yet. Pay with UPI, scan a QR,"
                + " or import a statement.");
          } else {
            for (LedgerTransaction row : recent) homeRecent.addView(transactionRow(row));
          }
        }
        if (!pending.isEmpty() && pendingTxnId.isEmpty()) {
          pendingTxnId = pending.get(0).id;
          refreshPendingBanner();
        }
      });
    });
  }

  private void refreshPendingBanner() {
    if (homeBanner == null) return;
    homeBanner.removeAllViews();
    if (pendingTxnId.isEmpty()) return;
    LinearLayout banner = Ui.card(activity);
    banner.setBackground(Ui.panel(activity, 0xff241a06, Ui.AMBER, 9));
    banner.addView(Ui.label(activity, "PAYMENT RESULT NOT RECEIVED", 9, Ui.AMBER));
    banner.addView(Ui.label(activity, "The UPI app did not return a status. Check the provider's"
        + " history, then record what happened. This is not treated as success.", 9.5f, Ui.TEXT));
    LinearLayout actions = Ui.row(activity);
    actions.addView(Ui.button(activity, "I COMPLETED IT", () -> resolvePending(true)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 34), 1f));
    actions.addView(Ui.button(activity, "IT FAILED", () -> resolvePending(false)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 34), 1f));
    banner.addView(actions);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 4));
    homeBanner.addView(banner, params);
  }

  private void resolvePending(boolean completed) {
    final String id = pendingTxnId;
    if (id.isEmpty()) return;
    work("pending-update-failed", () -> {
      LedgerTransaction transaction = store.byId(id);
      if (transaction == null) return;
      transaction.execution = completed
          ? LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS
          : LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE;
      if (completed) transaction.reconciliation = LedgerTransaction.Reconciliation.USER_CONFIRMED;
      store.update(transaction);
      ui(() -> {
        pendingTxnId = "";
        setStatus(completed ? "Recorded as paid \u00b7 still unverified by a statement"
            : "Recorded as failed", completed ? Ui.AMBER : Ui.MUTED);
        refreshPendingBanner();
        refreshHome();
      });
    });
  }

  // ------------------------------------------------------------------ pay

  private View buildPay() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Pay a UPI ID", 13, Ui.TEXT));
    payUpi = Ui.input(activity, "UPI ID  e.g. name@bank");
    payUpi.setText(draft.payeeAddress);
    payName = Ui.input(activity, "Beneficiary name (optional)");
    payName.setText(draft.payeeName);
    payAmount = Ui.numberInput(activity, "Amount in rupees");
    if (draft.amountPaise > 0) payAmount.setText(Money.rupees(draft.amountPaise));
    payNote = Ui.input(activity, "Note (optional)");
    payNote.setText(draft.note);
    page.addView(payUpi, Ui.full(44, activity));
    page.addView(payName, Ui.full(44, activity));
    page.addView(payAmount, Ui.full(44, activity));
    page.addView(payNote, Ui.full(44, activity));

    section(page, "PAY WITH");
    HorizontalScrollView providers = new HorizontalScrollView(activity);
    providers.setHorizontalScrollBarEnabled(false);
    LinearLayout providerRow = Ui.row(activity);
    providerRow.addView(providerChip("Generic UPI", ""), new LinearLayout.LayoutParams(-2, Ui.dp(activity, 34)));
    for (Providers.Provider provider : Providers.installed(activity))
      providerRow.addView(providerChip(provider.name, provider.packageName),
          new LinearLayout.LayoutParams(-2, Ui.dp(activity, 34)));
    providers.addView(providerRow, new HorizontalScrollView.LayoutParams(-2, -1));
    page.addView(providers, Ui.full(36, activity));
    page.addView(Ui.label(activity, "Your UPI app asks for the account and UPI PIN. oPay never"
        + " sees, stores or asks for a PIN or OTP.", 9, Ui.MUTED));

    Button pay = Ui.primary(activity, "PAY WITH UPI", this::submitPayment);
    LinearLayout.LayoutParams payParams = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 46));
    payParams.setMargins(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 6));
    page.addView(pay, payParams);

    section(page, "RECENT BENEFICIARIES");
    LinearLayout beneficiaries = Ui.column(activity);
    page.addView(beneficiaries, new LinearLayout.LayoutParams(-1, -2));
    work("beneficiaries-failed", () -> {
      List<OpayStore.Beneficiary> list = store.beneficiaries("");
      ui(() -> {
        beneficiaries.removeAllViews();
        if (list.isEmpty()) {
          Ui.message(beneficiaries, activity, "Saved beneficiaries appear here after your first"
              + " payment. Add them in MORE \u2192 Beneficiaries.");
          return;
        }
        int shown = 0;
        for (OpayStore.Beneficiary beneficiary : list) {
          if (shown++ >= 8) break;
          beneficiaries.addView(beneficiaryRow(beneficiary, () -> {
            payUpi.setText(beneficiary.upi);
            payName.setText(beneficiary.name);
          }));
        }
      });
    });
    return page;
  }

  private Button providerChip(String label, String packageName) {
    Button chip = Ui.button(activity, label, () -> {
      providerPackage = packageName;
      providerName = packageName.isEmpty() ? "Generic UPI"
          : Providers.providerNameForPackage(packageName);
      setStatus("Paying with " + providerName, Ui.CYAN);
      for (int index = 0; index < nav.getChildCount(); index++) { /* no-op */ }
      show("pay");
    });
    chip.setTextSize(9);
    boolean active = providerPackage.equals(packageName);
    chip.setTextColor(active ? 0xff000000 : Ui.TEXT);
    chip.setBackground(Ui.panel(activity, active ? Ui.CYAN : Ui.SURFACE_ALT,
        active ? Ui.CYAN : Ui.BORDER, 7));
    return chip;
  }

  private void submitPayment() {
    Upi.Request request;
    try {
      request = new Upi.Request();
      request.payeeAddress = payUpi.getText().toString().trim();
      request.payeeName = payName.getText().toString().trim();
      String amountText = payAmount.getText().toString().trim();
      Long paise = Money.parse(amountText);
      if (paise == null || paise <= 0) throw new IllegalArgumentException("Enter an amount");
      request.amountPaise = paise;
      request.note = payNote.getText().toString().trim();
      request.currency = Money.INR;
      Upi.build(request);
    } catch (IllegalArgumentException error) {
      setStatus(error.getMessage(), Ui.RED);
      return;
    }
    draft = request;
    startPayment(request);
  }

  private void startPayment(Upi.Request request) {
    LedgerTransaction transaction = new LedgerTransaction();
    transaction.direction = LedgerTransaction.Direction.DEBIT;
    transaction.amountPaise = request.amountPaise;
    transaction.provider = providerName;
    transaction.counterparty = request.payeeName;
    transaction.upiAddress = request.payeeAddress;
    transaction.note = request.note;
    transaction.execution = LedgerTransaction.Execution.CREATED;
    transaction.origin = LedgerTransaction.Origin.UPI;
    transaction.category = "Other";
    try {
      List<OpayStore.Account> accounts = store.accounts();
      if (!accounts.isEmpty()) {
        transaction.accountId = accounts.get(0).id;
        transaction.accountMasked = accounts.get(0).masked;
      }
    } catch (RuntimeException error) {
      hostLog("account-read-failed", error);
    }
    store.insert(transaction);
    pendingTxnId = transaction.id;

    if (prefs.getBoolean("gps", true) && LocationFix.permitted(activity)) {
      LocationFix.capture(activity, (latitude, longitude, accuracy, provider, time) -> {
        if (latitude == null || longitude == null) return;
        transaction.latitude = latitude;
        transaction.longitude = longitude;
        transaction.locationAccuracy = accuracy;
        transaction.locationProvider = provider == null ? "" : provider;
        transaction.locationAt = time;
        work("location-save-failed", () -> store.update(transaction));
        setStatus("GPS fix attached \u00b7 " + String.format(Locale.US, "%.5f, %.5f",
            latitude, longitude), Ui.CYAN);
      });
    } else if (prefs.getBoolean("gps", true)) {
      setStatus("Payment continues without a GPS fix (location not allowed).", Ui.AMBER);
    }

    transaction.execution = LedgerTransaction.Execution.INITIATED;
    store.update(transaction);
    Intent intent = Providers.payIntent(request, providerPackage);
    Intent launch = providerPackage.isEmpty()
        ? Intent.createChooser(intent, "Pay with UPI") : intent;
    try {
      activity.startActivityForResult(launch, REQ_PAY);
      work("beneficiary-save-failed",
          () -> store.touchBeneficiary(request.payeeAddress, request.payeeName));
      setStatus("Handed to " + providerName + " \u00b7 awaiting result", Ui.AMBER);
    } catch (android.content.ActivityNotFoundException missing) {
      transaction.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE;
      store.update(transaction);
      pendingTxnId = "";
      hostLog("payment-launch-failed", missing);
      setStatus("No UPI app handled the payment. Install a UPI app or use Generic UPI.", Ui.RED);
    } catch (RuntimeException error) {
      transaction.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE;
      store.update(transaction);
      pendingTxnId = "";
      hostLog("payment-launch-failed", error);
      setStatus("Could not open the UPI app: " + message(error), Ui.RED);
    }
  }

  // ------------------------------------------------------------------ payment result

  private void handlePaymentResult(int resultCode, Intent data) {
    final String id = pendingTxnId;
    if (id.isEmpty()) {
      setStatus("Provider returned a result with no pending transaction.", Ui.AMBER);
      return;
    }
    String response = Providers.responseText(resultCode, data);
    final boolean success = Providers.reportedSuccess(data);
    final boolean failure = Providers.reportedFailure(data);
    final String txnRef = field(data, "txnId", "transactionId", "Transaction ID", "UPI_TID");
    final String utr = field(data, "approvalRefNo", "approvalRef", "rrn", "UTR");
    work("payment-result-failed", () -> {
      LedgerTransaction transaction = store.byId(id);
      if (transaction == null) return;
      transaction.execution = success
          ? LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS
          : failure ? LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE
          : LedgerTransaction.Execution.UNKNOWN;
      if (!txnRef.isEmpty()) transaction.transactionId = txnRef;
      if (!utr.isEmpty()) transaction.utr = utr;
      transaction.annotations = (transaction.annotations.isEmpty() ? "" : transaction.annotations + "\n")
          + response;
      store.update(transaction);
      ui(() -> {
        pendingTxnId = "";
        setStatus(success ? "Provider reports success \u00b7 still unverified"
            : failure ? "Provider reports failure" : "Provider returned no clear status",
            success ? Ui.AMBER : failure ? Ui.RED : Ui.MUTED);
        showDetail(id);
        refreshPendingBanner();
      });
    });
  }

  private static String field(Intent data, String... keys) {
    if (data == null || data.getExtras() == null) return "";
    Bundle extras = data.getExtras();
    for (String key : keys) {
      Object value = extras.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty())
        return String.valueOf(value).trim();
    }
    return "";
  }

  @Override public void activityResult(int request, int result, Intent data) {
    try {
      switch (request) {
        case REQ_PAY: handlePaymentResult(result, data); break;
        case REQ_QR_GALLERY: handleGalleryQr(result, data); break;
        case REQ_IMPORT: handleImportFile(result, data); break;
        case REQ_EXPORT: handleExportWrite(result, data); break;
        case REQ_BACKUP: handleExportWrite(result, data); break;
        case REQ_RESTORE: handleRestoreRead(result, data); break;
        default: break;
      }
    } catch (RuntimeException error) {
      hostLog("activity-result-failed", error);
      setStatus("oPay could not handle the result: " + message(error), Ui.RED);
    }
  }

  @Override public void permissionResult(int request, String[] permissions, int[] grants) {
    if (request != REQ_PERMISSION && request != REQ_CAMERA && request != REQ_LOCATION) return;
    int denied = 0;
    for (int grant : grants) if (grant != android.content.pm.PackageManager.PERMISSION_GRANTED) denied++;
    if (denied > 0) {
      hostLog("permission-denied", denied + " of " + grants.length + " denied");
      setStatus("Permission denied \u00b7 the action was cancelled", Ui.RED);
      return;
    }
    if (request == REQ_CAMERA) openScanner();
    else if (request == REQ_LOCATION) setStatus("Location allowed \u00b7 the next payment"
        + " records a GPS snapshot.", Ui.GREEN);
  }

  // ------------------------------------------------------------------ receive

  private View buildReceive() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Receive with your own UPI ID", 13, Ui.TEXT));
    page.addView(Ui.label(activity, "Generate a personal or fixed-amount UPI QR. The QR contains"
        + " only your UPI ID, name and optional amount; no bank details.", 9.5f, Ui.MUTED));
    receiveUpi = Ui.input(activity, "Your UPI ID  e.g. name@bank");
    receiveUpi.setText(prefs.getString("upi", ""));
    receiveName = Ui.input(activity, "Your name");
    receiveName.setText(prefs.getString("upiName", ""));
    receiveAmount = Ui.numberInput(activity, "Amount in rupees (blank = any amount)");
    receiveNote = Ui.input(activity, "Note (optional)");
    page.addView(receiveUpi, Ui.full(44, activity));
    page.addView(receiveName, Ui.full(44, activity));
    page.addView(receiveAmount, Ui.full(44, activity));
    page.addView(receiveNote, Ui.full(44, activity));
    page.addView(Ui.accent(activity, "GENERATE QR", this::generateReceiveQr), Ui.full(42, activity));
    receiveQr = new ImageView(activity);
    receiveQr.setAdjustViewBounds(true);
    receiveQr.setBackgroundColor(0xffffffff);
    receiveQr.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 6), Ui.dp(activity, 6), Ui.dp(activity, 6));
    LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(Ui.dp(activity, 260),
        Ui.dp(activity, 260));
    qrParams.gravity = Gravity.CENTER_HORIZONTAL;
    qrParams.setMargins(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 4));
    page.addView(receiveQr, qrParams);
    final TextView uriView = Ui.mono(activity, "", 9, Ui.CYAN);
    uriView.setTextIsSelectable(true);
    page.addView(uriView, new LinearLayout.LayoutParams(-1, -2));
    page.addView(Ui.label(activity, "Anyone can inspect a receiving QR. Share it only with the"
        + " payer and verify the amount received in your bank app.", 9, Ui.MUTED));
    return page;
  }

  private void generateReceiveQr() {
    try {
      String address = receiveUpi.getText().toString().trim();
      if (!Upi.validPayee(address))
        throw new IllegalArgumentException("Enter a valid UPI ID such as name@bank");
      String amountText = receiveAmount.getText().toString().trim();
      long paise = 0;
      if (!amountText.isEmpty()) {
        Long parsed = Money.parse(amountText);
        if (parsed == null || parsed <= 0) throw new IllegalArgumentException("Invalid amount");
        paise = parsed;
      }
      String uri = Upi.receive(address, receiveName.getText().toString().trim(), paise,
          receiveNote.getText().toString().trim());
      lastQr = QrTools.encode(uri, 640);
      receiveQr.setImageBitmap(lastQr);
      prefs.edit().putString("upi", address)
          .putString("upiName", receiveName.getText().toString().trim()).apply();
      setStatus("Receiving QR ready · " + (paise == 0 ? "any amount" : Money.format(paise)),
          Ui.GREEN);
      LinearLayout parent = (LinearLayout) receiveQr.getParent();
      int index = parent.indexOfChild(receiveQr) + 1;
      if (index < parent.getChildCount() && parent.getChildAt(index) instanceof TextView)
        ((TextView) parent.getChildAt(index)).setText(uri);
    } catch (Exception error) {
      hostLog("receive-qr-failed", error);
      setStatus("QR could not be generated: " + message(error), Ui.RED);
    }
  }

  // ------------------------------------------------------------------ ledger

  private View buildLedger() {
    LinearLayout page = Ui.column(activity);
    ledgerSearch = Ui.input(activity, "Search beneficiary, UPI, reference, note, category");
    ledgerSearch.setText(ledgerQuery);
    ledgerSearch.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
      @Override public void afterTextChanged(Editable s) {
        ledgerQuery = s.toString();
        if (pendingSearch != null) searchDelay.removeCallbacks(pendingSearch);
        pendingSearch = () -> loadLedger(true);
        searchDelay.postDelayed(pendingSearch, 250);
      }
    });
    page.addView(ledgerSearch, Ui.full(44, activity));
    LinearLayout filters = Ui.row(activity);
    for (String[] filter : new String[][]{{"ALL", "All"}, {"CREDIT", "In"},
        {"DEBIT", "Out"}, {"UNVERIFIED", "Unverified"}}) {
      Button chip = Ui.button(activity, filter[1], () -> {
        ledgerFilter = filter[0];
        show("ledger");
      });
      boolean active = ledgerFilter.equals(filter[0]);
      chip.setTextColor(active ? 0xff000000 : Ui.TEXT);
      chip.setBackground(Ui.panel(activity, active ? Ui.CYAN : Ui.SURFACE_ALT,
          active ? Ui.CYAN : Ui.BORDER, 7));
      filters.addView(chip, new LinearLayout.LayoutParams(0, Ui.dp(activity, 32), 1f));
    }
    page.addView(filters);
    LinearLayout sortRow = Ui.row(activity);
    sortRow.addView(Ui.label(activity, ledgerSort.equals("date_asc") ? "Oldest first" : "Newest first",
        9, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1f));
    sortRow.addView(Ui.button(activity, "SORT", () -> {
      ledgerSort = ledgerSort.equals("date_asc") ? "date_desc" : "date_asc";
      show("ledger");
    }), Ui.wrap());
    page.addView(sortRow);
    ledgerList = Ui.column(activity);
    page.addView(ledgerList, new LinearLayout.LayoutParams(-1, -2));
    loadMore = Ui.button(activity, "LOAD MORE", () -> loadLedger(false));
    loadMore.setVisibility(View.GONE);
    page.addView(loadMore, Ui.full(38, activity));
    page.addView(Ui.label(activity, "Rows are paged 60 at a time; reports and searches run on a"
        + " background thread over the local SQLite ledger.", 8.5f, Ui.MUTED));
    loadLedger(true);
    return page;
  }

  private String ledgerQuery = "", ledgerSort = "date_desc";

  private void loadLedger(boolean reset) {
    if (reset) {
      ledgerOffset = 0;
      ledgerRows.clear();
    }
    final String query = ledgerQuery == null ? "" : ledgerQuery.trim();
    final String filter = ledgerFilter;
    final String sort = ledgerSort;
    final int offset = ledgerOffset;
    if (ledgerList != null && reset) Ui.message(ledgerList, activity, "Reading ledger\u2026");
    work("ledger-read-failed", () -> {
      OpayStore.Filters filters = new OpayStore.Filters();
      filters.query = query;
      filters.sort = sort;
      filters.offset = offset;
      filters.limit(60);
      if ("CREDIT".equals(filter) || "DEBIT".equals(filter)) filters.direction = filter;
      filters.unreconciled = "UNVERIFIED".equals(filter);
      List<LedgerTransaction> rows = store.query(filters);
      int total = store.count();
      ui(() -> {
        if (ledgerList == null || !screen.equals("ledger")) return;
        if (reset) ledgerList.removeAllViews();
        ledgerRows.addAll(rows);
        ledgerOffset = ledgerRows.size();
        if (rows.isEmpty() && ledgerRows.isEmpty()) {
          Ui.message(ledgerList, activity, query.isEmpty() ? "No transactions yet."
              : "No transactions match \"" + query + "\".");
        } else {
          for (LedgerTransaction row : rows) ledgerList.addView(transactionRow(row));
        }
        loadMore.setVisibility(rows.size() == 60 ? View.VISIBLE : View.GONE);
        setStatus(ledgerRows.size() + " shown \u00b7 " + total + " ledger records", Ui.MUTED);
      });
    });
  }

  private View transactionRow(LedgerTransaction transaction) {
    LinearLayout row = Ui.row(activity);
    row.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 8), Ui.dp(activity, 6));
    row.setBackground(Ui.panel(activity, Ui.SURFACE, Ui.BORDER, 8));
    LinearLayout texts = Ui.column(activity);
    String title = transaction.counterparty.isEmpty()
        ? (transaction.upiAddress.isEmpty() ? transaction.category : transaction.upiAddress)
        : transaction.counterparty;
    TextView name = Ui.label(activity, title.isEmpty() ? "(no counterparty)" : title, 12, Ui.TEXT);
    name.setSingleLine(true);
    texts.addView(name);
    String detail = OpayStore.dateLabel(transaction.occurredAt) + " \u00b7 "
        + (transaction.provider.isEmpty() ? "Manual" : transaction.provider)
        + (transaction.hasLocation() ? " \u00b7 GPS" : "");
    texts.addView(Ui.label(activity, detail, 8.5f, Ui.MUTED));
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
    LinearLayout amounts = Ui.column(activity);
    boolean credit = transaction.direction == LedgerTransaction.Direction.CREDIT;
    TextView amount = Ui.label(activity, (credit ? "+" : "\u2212")
        + Money.format(transaction.amountPaise), 12, credit ? Ui.GREEN : Ui.TEXT);
    amount.setGravity(Gravity.END);
    amounts.addView(amount);
    TextView state = Ui.label(activity, stateLabel(transaction), 8, stateColor(transaction));
    state.setGravity(Gravity.END);
    amounts.addView(state);
    row.addView(amounts, new LinearLayout.LayoutParams(-2, -2));
    row.setOnClickListener(view -> showDetail(transaction.id));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 2));
    row.setLayoutParams(params);
    return row;
  }

  static String stateLabel(LedgerTransaction transaction) {
    if (transaction.reconciliation == LedgerTransaction.Reconciliation.STATEMENT_MATCHED)
      return "RECONCILED";
    if (transaction.reconciliation == LedgerTransaction.Reconciliation.USER_CONFIRMED)
      return "CONFIRMED";
    if (transaction.reconciliation == LedgerTransaction.Reconciliation.REFUNDED)
      return "REFUNDED";
    if (transaction.reconciliation == LedgerTransaction.Reconciliation.REVERSED)
      return "REVERSED";
    if (transaction.reconciliation == LedgerTransaction.Reconciliation.CONFLICT)
      return "CONFLICT";
    if (transaction.execution == LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE)
      return "FAILED";
    if (transaction.execution == LedgerTransaction.Execution.CANCELLED) return "CANCELLED";
    if (transaction.execution == LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS)
      return "PROVIDER SAYS OK";
    if (transaction.execution == LedgerTransaction.Execution.PROVIDER_REPORTED_PENDING
        || transaction.execution == LedgerTransaction.Execution.INITIATED) return "PENDING";
    return "UNVERIFIED";
  }

  static int stateColor(LedgerTransaction transaction) {
    String label = stateLabel(transaction);
    if (label.equals("RECONCILED") || label.equals("CONFIRMED")) return Ui.GREEN;
    if (label.equals("FAILED") || label.equals("CANCELLED")) return Ui.RED;
    if (label.equals("REFUNDED")) return Ui.PURPLE;
    if (label.equals("REVERSED")) return Ui.PINK;
    if (label.equals("PENDING")) return Ui.AMBER;
    return Ui.MUTED;
  }

  // ------------------------------------------------------------------ detail

  private LedgerTransaction lastDetail;

  private void showDetail(String id) {
    work("detail-read-failed", () -> {
      LedgerTransaction transaction = store.byId(id);
      ui(() -> {
        if (transaction == null) {
          setStatus("That transaction no longer exists.", Ui.RED);
          return;
        }
        lastDetail = transaction;
        screen = "detail";
        for (Map.Entry<String, Button> entry : navButtons.entrySet())
          Ui.paintChip(entry.getValue(), entry.getKey().equals("ledger"));
        body.removeAllViews();
        scroll.scrollTo(0, 0);
        body.addView(buildDetail(transaction), new LinearLayout.LayoutParams(-1, -2));
      });
    });
  }

  private View buildDetail(LedgerTransaction transaction) {
    LinearLayout page = Ui.column(activity);
    LinearLayout head = Ui.card(activity);
    boolean credit = transaction.direction == LedgerTransaction.Direction.CREDIT;
    TextView amount = Ui.label(activity, (credit ? "+" : "\u2212")
        + Money.format(transaction.amountPaise), 26, credit ? Ui.GREEN : Ui.TEXT);
    amount.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
        android.graphics.Typeface.BOLD));
    head.addView(amount);
    head.addView(Ui.label(activity, stateLabel(transaction) + " \u00b7 " + transaction.origin.name()
        + " \u00b7 " + transaction.currency, 10, stateColor(transaction)));
    head.addView(Ui.label(activity, transaction.counterparty.isEmpty()
        ? transaction.upiAddress : transaction.counterparty, 12, Ui.TEXT));
    page.addView(head);

    LinearLayout facts = Ui.card(activity);
    fact(facts, "Date", new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
        .format(new Date(transaction.occurredAt)));
    fact(facts, "Direction", transaction.direction.name());
    fact(facts, "Provider", transaction.provider.isEmpty() ? "Manual" : transaction.provider);
    fact(facts, "UPI ID", transaction.upiAddress);
    fact(facts, "Account", transaction.accountMasked);
    fact(facts, "Transaction ID", transaction.transactionId);
    fact(facts, "UTR / RRN", transaction.utr);
    fact(facts, "Category", transaction.category);
    fact(facts, "Execution", transaction.execution.name());
    fact(facts, "Reconciliation", transaction.reconciliation.name());
    fact(facts, "Import batch", transaction.importBatch);
    fact(facts, "Note", transaction.note);
    if (transaction.internalTransfer) fact(facts, "Transfer", "Internal account transfer");
    if (!transaction.relatedId.isEmpty()) fact(facts, "Linked record", transaction.relatedId);
    page.addView(facts);

    if (transaction.hasLocation()) {
      LinearLayout location = Ui.card(activity);
      location.addView(Ui.label(activity, "GPS SNAPSHOT AT PAYMENT", 9, Ui.CYAN));
      location.addView(Ui.mono(activity, transaction.locationText(), 10, Ui.TEXT));
      location.addView(Ui.label(activity, "Location is stored only in this ledger and is included"
          + " in exports/backups you create.", 8.5f, Ui.MUTED));
      location.setOnClickListener(view -> {
        copy(transaction.latitude + ", " + transaction.longitude);
        setStatus("Coordinates copied", Ui.CYAN);
      });
      page.addView(location);
    }

    if (!transaction.annotations.isEmpty()) {
      LinearLayout provider = Ui.card(activity);
      provider.addView(Ui.label(activity, "PROVIDER / USER RESPONSE", 9, Ui.PURPLE));
      TextView bodyText = Ui.label(activity, transaction.annotations, 9, Ui.MUTED);
      bodyText.setTextIsSelectable(true);
      provider.addView(bodyText);
      page.addView(provider);
    }

    LinearLayout actions = Ui.row(activity);
    actions.addView(Ui.button(activity, "CONFIRM\nIN BANK", () -> mark(transaction,
        LedgerTransaction.Reconciliation.USER_CONFIRMED)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 40), 1f));
    actions.addView(Ui.button(activity, "RECONCILE", () -> openReconcileFor(transaction)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 40), 1f));
    actions.addView(Ui.button(activity, "NOTE", () -> editNote(transaction)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 40), 1f));
    page.addView(actions);

    LinearLayout actions2 = Ui.row(activity);
    actions2.addView(Ui.button(activity, "REFUNDED", () -> mark(transaction,
        LedgerTransaction.Reconciliation.REFUNDED)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 34), 1f));
    actions2.addView(Ui.button(activity, "REVERSED", () -> mark(transaction,
        LedgerTransaction.Reconciliation.REVERSED)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 34), 1f));
    actions2.addView(Ui.button(activity, "DELETE", () -> confirmDelete(transaction)),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 34), 1f));
    page.addView(actions2);
    page.addView(Ui.label(activity, "A provider success is not settlement. Import a bank"
        + " statement or confirm in your bank app before treating money as received.",
        9, Ui.MUTED));
    return page;
  }

  private void fact(LinearLayout parent, String name, String value) {
    if (value == null || value.isEmpty()) return;
    LinearLayout row = Ui.row(activity);
    row.addView(Ui.label(activity, name, 9, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 0.38f));
    TextView content = Ui.label(activity, value, 9.5f, Ui.TEXT);
    content.setTextIsSelectable(true);
    row.addView(content, new LinearLayout.LayoutParams(0, -2, 0.62f));
    parent.addView(row);
  }

  private void mark(LedgerTransaction transaction, LedgerTransaction.Reconciliation state) {
    final String id = transaction.id;
    work("detail-update-failed", () -> {
      LedgerTransaction current = store.byId(id);
      if (current == null) return;
      current.reconciliation = state;
      store.update(current);
      ui(() -> {
        setStatus("Marked " + state.name().toLowerCase(Locale.US), Ui.GREEN);
        showDetail(id);
      });
    });
  }

  private void editNote(LedgerTransaction transaction) {
    EditText input = Ui.input(activity, "Note or annotation");
    input.setText(transaction.note);
    input.setSingleLine(false);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    new AlertDialog.Builder(activity).setTitle("Transaction note")
        .setView(input)
        .setPositiveButton("SAVE", (dialog, which) -> {
          final String note = input.getText().toString().trim();
          work("note-save-failed", () -> {
            LedgerTransaction current = store.byId(transaction.id);
            if (current == null) return;
            current.note = note;
            store.update(current);
            ui(() -> showDetail(transaction.id));
          });
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void confirmDelete(LedgerTransaction transaction) {
    new AlertDialog.Builder(activity).setTitle("Delete transaction?")
        .setMessage("This removes the local ledger record. It does not cancel any payment.")
        .setPositiveButton("DELETE", (dialog, which) -> work("delete-failed", () -> {
          store.delete(transaction.id);
          ui(() -> {
            lastDetail = null;
            setStatus("Transaction deleted", Ui.MUTED);
            show("ledger");
          });
        }))
        .setNegativeButton("Cancel", null).show();
  }

  // ------------------------------------------------------------------ beneficiaries & accounts

  private View buildBeneficiaries() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Beneficiaries", 14, Ui.TEXT));
    page.addView(Ui.button(activity, "+ ADD BENEFICIARY", this::addBeneficiary), Ui.full(38, activity));
    LinearLayout list = Ui.column(activity);
    page.addView(list, new LinearLayout.LayoutParams(-1, -2));
    work("beneficiaries-failed", () -> {
      List<OpayStore.Beneficiary> beneficiaries = store.beneficiaries("");
      ui(() -> {
        list.removeAllViews();
        if (beneficiaries.isEmpty()) {
          Ui.message(list, activity, "No saved beneficiaries yet.");
          return;
        }
        for (OpayStore.Beneficiary beneficiary : beneficiaries) {
          LinearLayout card = Ui.card(activity);
          card.addView(Ui.label(activity, beneficiary.name.isEmpty() ? "(no name)" : beneficiary.name,
              11, Ui.TEXT));
          card.addView(Ui.label(activity, beneficiary.upi + " \u00b7 used " + beneficiary.uses
              + "\u00d7" + (beneficiary.note.isEmpty() ? "" : " \u00b7 " + beneficiary.note),
              9, Ui.MUTED));
          LinearLayout actions = Ui.row(activity);
          actions.addView(Ui.button(activity, "PAY", () -> {
            draft = new Upi.Request();
            draft.payeeAddress = beneficiary.upi;
            draft.payeeName = beneficiary.name;
            show("pay");
          }), new LinearLayout.LayoutParams(0, Ui.dp(activity, 32), 1f));
          actions.addView(Ui.button(activity, "DELETE", () -> work("beneficiary-delete-failed", () -> {
            store.deleteBeneficiary(beneficiary.id);
            ui(() -> show("beneficiaries"));
          })), new LinearLayout.LayoutParams(0, Ui.dp(activity, 32), 1f));
          card.addView(actions);
          list.addView(card);
        }
      });
    });
    return page;
  }

  private View beneficiaryRow(OpayStore.Beneficiary beneficiary, Runnable action) {
    LinearLayout row = Ui.row(activity);
    row.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 5), Ui.dp(activity, 8), Ui.dp(activity, 5));
    row.setBackground(Ui.panel(activity, Ui.SURFACE, Ui.BORDER, 8));
    LinearLayout texts = Ui.column(activity);
    texts.addView(Ui.label(activity, beneficiary.name.isEmpty() ? beneficiary.upi : beneficiary.name,
        11, Ui.TEXT));
    if (!beneficiary.name.isEmpty()) texts.addView(Ui.label(activity, beneficiary.upi, 9, Ui.CYAN));
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
    row.addView(Ui.label(activity, "USE", 9, Ui.CYAN));
    row.setOnClickListener(view -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 2));
    row.setLayoutParams(params);
    return row;
  }

  private void addBeneficiary() {
    LinearLayout form = Ui.column(activity);
    EditText name = Ui.input(activity, "Name");
    EditText upi = Ui.input(activity, "UPI ID  name@bank");
    EditText note = Ui.input(activity, "Note (optional)");
    form.addView(name);
    form.addView(upi);
    form.addView(note);
    form.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 4), Ui.dp(activity, 12), 0);
    new AlertDialog.Builder(activity).setTitle("Add beneficiary").setView(form)
        .setPositiveButton("SAVE", (dialog, which) -> {
          if (!Upi.validPayee(upi.getText().toString().trim())) {
            setStatus("Enter a valid UPI ID such as name@bank", Ui.RED);
            return;
          }
          OpayStore.Beneficiary beneficiary = new OpayStore.Beneficiary();
          beneficiary.name = name.getText().toString().trim();
          beneficiary.upi = upi.getText().toString().trim();
          beneficiary.note = note.getText().toString().trim();
          beneficiary.lastUsed = System.currentTimeMillis();
          beneficiary.uses = 0;
          work("beneficiary-save-failed", () -> {
            store.saveBeneficiary(beneficiary);
            ui(() -> show("beneficiaries"));
          });
        })
        .setNegativeButton("Cancel", null).show();
  }

  private View buildAccounts() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Bank accounts", 14, Ui.TEXT));
    page.addView(Ui.label(activity, "Accounts label statements and transactions. The first account"
        + " is used for new payments. oPay never stores full account numbers or bank credentials.",
        9, Ui.MUTED));
    page.addView(Ui.button(activity, "+ ADD ACCOUNT", this::addAccount), Ui.full(38, activity));
    LinearLayout list = Ui.column(activity);
    page.addView(list, new LinearLayout.LayoutParams(-1, -2));
    work("accounts-failed", () -> {
      List<OpayStore.Account> accounts = store.accounts();
      ui(() -> {
        list.removeAllViews();
        if (accounts.isEmpty()) {
          Ui.message(list, activity, "No accounts yet. Add a masked account label.");
          return;
        }
        for (int index = 0; index < accounts.size(); index++) {
          OpayStore.Account account = accounts.get(index);
          LinearLayout card = Ui.card(activity);
          card.addView(Ui.label(activity, account.label() + (index == 0 ? " \u00b7 DEFAULT" : ""),
              11, index == 0 ? Ui.CYAN : Ui.TEXT));
          if (!account.upi.isEmpty()) card.addView(Ui.label(activity, account.upi, 9, Ui.MUTED));
          card.addView(Ui.button(activity, "DELETE", () -> work("account-delete-failed", () -> {
            store.deleteAccount(account.id);
            ui(() -> show("accounts"));
          })), Ui.full(30, activity));
          list.addView(card);
        }
      });
    });
    return page;
  }

  private void addAccount() {
    LinearLayout form = Ui.column(activity);
    EditText name = Ui.input(activity, "Account name");
    EditText bank = Ui.input(activity, "Bank");
    EditText masked = Ui.input(activity, "Masked number  e.g. XX1234");
    EditText upi = Ui.input(activity, "Linked UPI ID (optional)");
    form.addView(name);
    form.addView(bank);
    form.addView(masked);
    form.addView(upi);
    form.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 4), Ui.dp(activity, 12), 0);
    new AlertDialog.Builder(activity).setTitle("Add account").setView(form)
        .setPositiveButton("SAVE", (dialog, which) -> {
          OpayStore.Account account = new OpayStore.Account();
          account.name = name.getText().toString().trim();
          account.bank = bank.getText().toString().trim();
          account.masked = masked.getText().toString().trim();
          account.upi = upi.getText().toString().trim();
          work("account-save-failed", () -> {
            store.saveAccount(account);
            ui(() -> show("accounts"));
          });
        })
        .setNegativeButton("Cancel", null).show();
  }

  // ------------------------------------------------------------------ import

  private LinearLayout importPreview;

  private View buildImport() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Import historical transactions", 14, Ui.TEXT));
    page.addView(Ui.label(activity, "Statements stay on this phone. oPay reads only the file you"
        + " pick through Android's document picker; it never opens another app's private data.",
        9, Ui.MUTED));
    LinearLayout actions = Ui.row(activity);
    actions.addView(Ui.accent(activity, "PICK FILE", this::pickImportFile),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 38), 1f));
    actions.addView(Ui.button(activity, "PASTE SMS", this::pasteSms),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 38), 1f));
    page.addView(actions);
    page.addView(Ui.label(activity, "CSV and XLSX use automatic column detection with a manual"
        + " mapping override. PDF needs embedded text (scanned statements are rejected)."
        + " JSON arrays and bank/payment SMS text are also accepted.", 9, Ui.MUTED));
    importPreview = Ui.column(activity);
    page.addView(importPreview, new LinearLayout.LayoutParams(-1, -2));
    renderImportPreview();
    section(page, "HOW DUPLICATES ARE HANDLED");
    page.addView(Ui.label(activity, "Rows with the same UTR/RRN or provider reference are"
        + " inserted once. Re-importing the same statement is idempotent. Amounts that merely"
        + " look similar are offered for review, never merged silently.", 9, Ui.MUTED));
    return page;
  }

  private void pickImportFile() {
    try {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      activity.startActivityForResult(intent, REQ_IMPORT);
    } catch (RuntimeException error) {
      hostLog("import-picker-failed", error);
      setStatus("No document picker available: " + message(error), Ui.RED);
    }
  }

  private void handleImportFile(int result, Intent data) {
    if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
    final Uri uri = data.getData();
    final String name = displayName(uri);
    setStatus("Reading " + name + "\u2026", Ui.MUTED);
    work("import-failed", () -> {
      byte[] bytes = readAll(uri, 32 * 1024 * 1024);
      StatementImport.Result parsed = StatementImport.parse(name, bytes, "", "");
      ui(() -> {
        importResult = parsed;
        importRows = new ArrayList<>(parsed.rows);
        importFileName = name;
        show("import");
        setStatus(parsed.summary(), parsed.rows.isEmpty() ? Ui.RED : Ui.AMBER);
      });
    });
  }

  private void pasteSms() {
    EditText input = Ui.input(activity, "Paste one or more bank/payment SMS messages");
    input.setSingleLine(false);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    input.setMinLines(5);
    new AlertDialog.Builder(activity).setTitle("Transaction SMS").setView(input)
        .setPositiveButton("PARSE", (dialog, which) -> {
          String text = input.getText().toString().trim();
          if (text.isEmpty()) return;
          StatementImport.Result parsed = StatementImport.parseSmsText(text, "", "");
          importResult = parsed;
          importRows = new ArrayList<>(parsed.rows);
          importFileName = "pasted-sms.txt";
          show("import");
          setStatus(parsed.summary(), parsed.rows.isEmpty() ? Ui.RED : Ui.AMBER);
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void renderImportPreview() {
    if (importPreview == null) return;
    importPreview.removeAllViews();
    if (importResult == null) return;
    LinearLayout card = Ui.card(activity);
    card.addView(Ui.label(activity, importResult.summary(), 10, Ui.CYAN));
    card.addView(Ui.label(activity, importFileName, 8.5f, Ui.MUTED));
    for (String warning : importResult.warnings)
      card.addView(Ui.label(activity, "\u26a0 " + warning, 9, Ui.AMBER));
    if (importResult.needsMapping()) {
      card.addView(Ui.label(activity, "Column mapping could not be inferred. Map date and an"
          + " amount column below.", 9, Ui.AMBER));
      LinearLayout mapping = Ui.row(activity);
      for (int role = 0; role < StatementImport.ROLE_COUNT; role++) {
        final int currentRole = role;
        String header = importResult.mapping[role] < 0 ? "\u2014".concat("")
            : (importResult.mapping[role] < importResult.headers.size()
            ? importResult.headers.get(importResult.mapping[role]) : "?");
        Button chip = Ui.button(activity, StatementImport.ROLE_NAMES[role].toUpperCase(Locale.US)
            + ": " + clip(header, 10), () -> chooseColumn(currentRole));
        chip.setTextSize(8);
        mapping.addView(chip, new LinearLayout.LayoutParams(0, Ui.dp(activity, 32), 1f));
        if (role == 3) {
          importPreview.addView(mapping);
          mapping = Ui.row(activity);
        }
      }
      importPreview.addView(mapping);
    }
    int shown = 0;
    for (LedgerTransaction row : importRows) {
      if (shown++ >= 30) break;
      LinearLayout line = Ui.row(activity);
      line.addView(Ui.label(activity, new SimpleDateFormat("dd MMM", Locale.US)
          .format(new Date(row.occurredAt)), 8.5f, Ui.MUTED),
          new LinearLayout.LayoutParams(0, -2, 0.18f));
      line.addView(Ui.label(activity, clip(row.counterparty, 32), 9, Ui.TEXT),
          new LinearLayout.LayoutParams(0, -2, 0.52f));
      line.addView(Ui.label(activity, (row.direction == LedgerTransaction.Direction.CREDIT
          ? "+" : "\u2212") + Money.format(row.amountPaise), 9,
          row.direction == LedgerTransaction.Direction.CREDIT ? Ui.GREEN : Ui.TEXT),
          new LinearLayout.LayoutParams(0, -2, 0.3f));
      card.addView(line);
    }
    if (importRows.size() > shown)
      card.addView(Ui.label(activity, "\u2026 " + (importRows.size() - shown) + " more rows",
          8.5f, Ui.MUTED));
    LinearLayout buttons = Ui.row(activity);
    buttons.addView(Ui.primary(activity, "COMMIT " + importRows.size(), this::commitImport),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 40), 1f));
    buttons.addView(Ui.button(activity, "CANCEL", () -> {
      importResult = null;
      importRows = new ArrayList<>();
      show("import");
    }), new LinearLayout.LayoutParams(0, Ui.dp(activity, 40), 1f));
    card.addView(buttons);
    importPreview.addView(card);
  }

  private void chooseColumn(int role) {
    if (importResult == null) return;
    List<String> options = new ArrayList<>();
    options.add("None");
    options.addAll(importResult.headers);
    String[] items = options.toArray(new String[0]);
    new AlertDialog.Builder(activity).setTitle("Map " + StatementImport.ROLE_NAMES[role])
        .setItems(items, (dialog, which) -> {
          int[] mapping = importResult.mapping.clone();
          mapping[role] = which - 1;
          StatementImport.Result remapped = StatementImport.applyMapping(importResult, mapping,
              "", "");
          remapped.provider = importResult.provider;
          importResult = remapped;
          importRows = new ArrayList<>(remapped.rows);
          renderImportPreview();
          setStatus(remapped.summary(), remapped.rows.isEmpty() ? Ui.RED : Ui.AMBER);
        }).show();
  }

  private void commitImport() {
    if (importResult == null || importRows.isEmpty()) return;
    final List<LedgerTransaction> rows = new ArrayList<>(importRows);
    final String batch = UUID.randomUUID().toString();
    final String source = importResult.format.name();
    final String name = importFileName;
    work("import-commit-failed", () -> {
      int linked = 0;
      List<LedgerTransaction> fresh = new ArrayList<>();
      for (LedgerTransaction row : rows) {
        String key = OpayStore.strongKey(row);
        LedgerTransaction existing = key == null ? null : store.byStrongKey(key);
        if (existing != null) {
          Reconciliation.link(existing, row);
          store.update(existing);
          linked++;
          continue;
        }
        if (key == null && store.existsSoft(row)) {
          linked++;
          continue;
        }
        fresh.add(row);
      }
      int inserted = store.insertBatch(fresh, batch);
      store.saveBatch(batch, source, name, inserted, linked + (fresh.size() - inserted));
      final int linkedCount = linked;
      ui(() -> {
        importResult = null;
        importRows = new ArrayList<>();
        show("ledger");
        setStatus(inserted + " imported \u00b7 " + linkedCount + " already present/linked",
            Ui.GREEN);
      });
    });
  }

  // ------------------------------------------------------------------ reconciliation

  private View buildReconcile() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Reconciliation", 14, Ui.TEXT));
    page.addView(Ui.label(activity, "Strong keys (UTR/RRN and provider reference) match"
        + " automatically during import. Everything else needs your decision.", 9, Ui.MUTED));
    LinearLayout list = Ui.column(activity);
    page.addView(list, new LinearLayout.LayoutParams(-1, -2));
    work("reconcile-read-failed", () -> {
      List<LedgerTransaction> rows = store.unreconciled(80);
      ui(() -> {
        list.removeAllViews();
        if (rows.isEmpty()) {
          Ui.message(list, activity, "Nothing awaiting review. Import a statement to verify"
              + " provider successes and pending payments.");
          return;
        }
        for (LedgerTransaction row : rows) list.addView(transactionRow(row));
      });
    });
    return page;
  }

  private void openReconcileFor(LedgerTransaction transaction) {
    work("reconcile-match-failed", () -> {
      List<LedgerTransaction> candidates = store.matchingCandidates(transaction);
      List<Reconciliation.Match> matches = Reconciliation.candidates(transaction, candidates);
      ui(() -> {
        if (matches.isEmpty()) {
          new AlertDialog.Builder(activity).setTitle("No candidates")
              .setMessage("No other ledger record is close enough to this transaction."
                  + " Import the bank statement or provider history first.")
              .setPositiveButton("OK", null).show();
          return;
        }
        String[] labels = new String[matches.size()];
        for (int index = 0; index < matches.size(); index++) {
          Reconciliation.Match match = matches.get(index);
          labels[index] = match.target.counterparty + " \u00b7 "
              + Money.format(match.target.amountPaise) + " \u00b7 " + match.reason
              + " (" + match.score + ")" + (match.ambiguous ? " \u00b7 AMBIGUOUS" : "");
        }
        new AlertDialog.Builder(activity).setTitle("Link to which record?")
            .setItems(labels, (dialog, which) -> {
              Reconciliation.Match match = matches.get(which);
              work("reconcile-link-failed", () -> {
                LedgerTransaction left = store.byId(transaction.id);
                LedgerTransaction right = store.byId(match.target.id);
                if (left == null || right == null) return;
                Reconciliation.link(left, right);
                store.update(left);
                store.update(right);
                ui(() -> {
                  setStatus("Linked. Review both records; amounts are never merged silently.",
                      Ui.GREEN);
                  showDetail(transaction.id);
                });
              });
            }).show();
      });
    });
  }

  // ------------------------------------------------------------------ reports

  private View buildReports() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Reports", 14, Ui.TEXT));
    HorizontalScrollView ranges = new HorizontalScrollView(activity);
    ranges.setHorizontalScrollBarEnabled(false);
    LinearLayout rangeRow = Ui.row(activity);
    String[][] kinds = {{"TODAY", "Today"}, {"WEEK", "Week"}, {"MONTH", "Month"},
        {"QUARTER", "Quarter"}, {"FY", "FY"}, {"YEAR", "Year"}, {"CUSTOM", "Custom"}};
    for (String[] kind : kinds) {
      Button chip = Ui.button(activity, kind[1], () -> {
        if (kind[0].equals("CUSTOM")) { customRange(); return; }
        reportKind = kind[0];
        show("reports");
      });
      boolean active = reportKind.equals(kind[0]);
      chip.setTextColor(active ? 0xff000000 : Ui.TEXT);
      chip.setBackground(Ui.panel(activity, active ? Ui.CYAN : Ui.SURFACE_ALT,
          active ? Ui.CYAN : Ui.BORDER, 7));
      rangeRow.addView(chip, new LinearLayout.LayoutParams(-2, Ui.dp(activity, 32)));
    }
    ranges.addView(rangeRow, new HorizontalScrollView.LayoutParams(-2, -1));
    page.addView(ranges, Ui.full(36, activity));

    LinearLayout summaryCard = Ui.card(activity);
    TextView rangeLabel = Ui.label(activity, "", 10, Ui.CYAN);
    summaryCard.addView(rangeLabel);
    TextView totals = Ui.label(activity, "Reading ledger\u2026", 12, Ui.TEXT);
    totals.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
        android.graphics.Typeface.BOLD));
    summaryCard.addView(totals);
    TextView metrics = Ui.label(activity, "", 9, Ui.MUTED);
    summaryCard.addView(metrics);
    page.addView(summaryCard);

    TextView categoryTitle = Ui.label(activity, "", 9, Ui.MUTED);
    BarsView categoryBars = new BarsView(activity);
    TextView providerTitle = Ui.label(activity, "", 9, Ui.MUTED);
    BarsView providerBars = new BarsView(activity);
    BarsView dailyBars = new BarsView(activity);
    TextView dailyTitle = Ui.label(activity, "", 9, Ui.MUTED);
    BarsView beneficiaryBars = new BarsView(activity);
    TextView beneficiaryTitle = Ui.label(activity, "", 9, Ui.MUTED);
    page.addView(categoryTitle);
    page.addView(categoryBars, Ui.full(0, activity));
    page.addView(providerTitle);
    page.addView(providerBars, Ui.full(0, activity));
    page.addView(dailyTitle);
    page.addView(dailyBars, Ui.full(0, activity));
    page.addView(beneficiaryTitle);
    page.addView(beneficiaryBars, Ui.full(0, activity));

    LinearLayout exports = Ui.row(activity);
    exports.addView(Ui.button(activity, "CSV", () -> exportReport("CSV")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    exports.addView(Ui.button(activity, "JSON", () -> exportReport("JSON")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    exports.addView(Ui.button(activity, "PDF", () -> exportReport("PDF")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    page.addView(exports);
    page.addView(Ui.label(activity, "Unverified rows are " + (prefs.getBoolean("includeUnverified",
        false) ? "INCLUDED" : "excluded") + " (change in MORE). Failed and cancelled attempts"
        + " never count toward totals.", 8.5f, Ui.MUTED));

    final Reports.Range range = reportRange();
    reportRange = range;
    rangeLabel.setText(range.label);
    final boolean includeUnverified = prefs.getBoolean("includeUnverified", false);
    work("report-failed", () -> {
      Reports.Summary summary = store.summary(range, includeUnverified);
      List<LedgerTransaction> rows = store.range(range.from, range.toExclusive);
      ui(() -> {
        reportSummary = summary;
        totals.setText("Debits " + Money.format(summary.debits) + "  \u00b7  Credits "
            + Money.format(summary.credits) + "  \u00b7  Net " + Money.signed(summary.net));
        metrics.setText(summary.count + " transactions \u00b7 average "
            + Money.format(summary.averagePaise) + " \u00b7 largest debit "
            + Money.format(summary.largestDebit) + " \u00b7 largest credit "
            + Money.format(summary.largestCredit) + "\nUnreconciled " + summary.unreconciled
            + " \u00b7 pending " + summary.pending + " \u00b7 refunds " + summary.refunds
            + " \u00b7 reversals " + summary.reversals + " \u00b7 failed/cancelled "
            + summary.failedOrCancelled + " \u00b7 internal transfers "
            + summary.internalTransfers);
        categoryTitle.setText("SPEND BY CATEGORY");
        List<BarsView.Bar> categories = new ArrayList<>();
        for (Map.Entry<String, Long> entry : summary.categoryDebits.entrySet()) {
          if (categories.size() >= 9) break;
          categories.add(new BarsView.Bar(entry.getKey(), entry.getValue(), Ui.CYAN));
        }
        categoryBars.setBars(categories);
        providerTitle.setText("TRANSACTIONS BY PROVIDER");
        List<BarsView.Bar> providers = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : summary.providerCounts.entrySet())
          providers.add(new BarsView.Bar(entry.getKey(), entry.getValue() == null ? 0
              : entry.getValue().longValue(), Ui.PURPLE));
        providerBars.setBars(providers);
        dailyTitle.setText("DAILY SPEND (last 14 buckets)");
        List<BarsView.Bar> daily = new ArrayList<>();
        List<Reports.Day> days = summary.daily();
        int start = Math.max(0, days.size() - 14);
        SimpleDateFormat dayLabel = new SimpleDateFormat("dd MMM", Locale.US);
        for (int index = start; index < days.size(); index++) {
          Reports.Day day = days.get(index);
          daily.add(new BarsView.Bar(dayLabel.format(new Date(day.dayStart)), day.debits, Ui.GREEN));
        }
        dailyBars.setBars(daily);
        beneficiaryTitle.setText("TOP BENEFICIARIES (DEBITS)");
        List<BarsView.Bar> beneficiaries = new ArrayList<>();
        for (Map.Entry<String, Long> entry : summary.beneficiaryDebits.entrySet()) {
          if (beneficiaries.size() >= 8) break;
          beneficiaries.add(new BarsView.Bar(entry.getKey(), entry.getValue(), Ui.PINK));
        }
        beneficiaryBars.setBars(beneficiaries);
        setStatus(summary.count + " transactions \u00b7 " + range.label, Ui.MUTED);
        lastReportRows = rows;
      });
    });
    return page;
  }

  private List<LedgerTransaction> lastReportRows = new ArrayList<>();

  private Reports.Range reportRange() {
    long now = System.currentTimeMillis();
    switch (reportKind) {
      case "TODAY": return Reports.day(now);
      case "WEEK": return Reports.week(now);
      case "QUARTER": return Reports.quarter(now);
      case "FY": return Reports.financialYear(now);
      case "YEAR": return Reports.calendarYear(now);
      case "CUSTOM":
        if (reportRange != null) return reportRange;
        return Reports.month(now);
      default: return Reports.month(now);
    }
  }

  private void customRange() {
    LinearLayout form = Ui.column(activity);
    EditText from = Ui.input(activity, "From  yyyy-MM-dd");
    EditText to = Ui.input(activity, "To  yyyy-MM-dd");
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    format.setLenient(false);
    from.setText(format.format(new Date(System.currentTimeMillis() - 30L * 86_400_000L)));
    to.setText(format.format(new Date()));
    form.addView(from);
    form.addView(to);
    form.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 4), Ui.dp(activity, 12), 0);
    new AlertDialog.Builder(activity).setTitle("Custom range").setView(form)
        .setPositiveButton("APPLY", (dialog, which) -> {
          try {
            long start = format.parse(from.getText().toString().trim()).getTime();
            long end = format.parse(to.getText().toString().trim()).getTime();
            if (end < start) throw new IllegalArgumentException("End date is before start");
            reportRange = Reports.custom(start, end);
            reportKind = "CUSTOM";
            show("reports");
          } catch (Exception error) {
            setStatus("Invalid dates: " + message(error), Ui.RED);
          }
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void exportReport(String kind) {
    if (reportSummary == null) {
      setStatus("Open the report once so it can be built.", Ui.AMBER);
      return;
    }
    final Reports.Summary summary = reportSummary;
    final List<LedgerTransaction> rows = new ArrayList<>(lastReportRows);
    final Reports.Range range = reportRange;
    work("report-export-failed", () -> {
      byte[] payload;
      String mime;
      String name = "opay-report-" + new SimpleDateFormat("yyyyMMdd", Locale.US)
          .format(new Date()) + (kind.equals("PDF") ? ".pdf" : kind.equals("JSON")
          ? ".json" : ".csv");
      if (kind.equals("PDF")) {
        payload = Exporter.pdf(summary, rows, range.label, "Financial report \u00b7 oPay");
        mime = "application/pdf";
      } else if (kind.equals("JSON")) {
        payload = Exporter.json(rows).getBytes(StandardCharsets.UTF_8);
        mime = "application/json";
      } else {
        payload = Exporter.csv(rows).getBytes(StandardCharsets.UTF_8);
        mime = "text/csv";
      }
      final byte[] bytes = payload;
      final String type = mime;
      ui(() -> requestExport(type, name, bytes));
    });
  }

  // ------------------------------------------------------------------ settings

  private View buildSettings() {
    LinearLayout page = Ui.column(activity);
    page.addView(Ui.label(activity, "Settings & diagnostics", 14, Ui.TEXT));
    LinearLayout toggles = Ui.card(activity);
    toggles.addView(toggle("gps", "Attach a GPS fix to every payment", true,
        "Coordinates, accuracy and fix time are saved with the transaction. Imported rows keep"
            + " whatever the statement contains."));
    toggles.addView(toggle("includeUnverified", "Include unverified rows in reports", false,
        "When off, only statement-matched or user-confirmed rows are totalled. Failed and"
            + " cancelled attempts are always excluded."));
    toggles.addView(toggle("lock", "Biometric lock", false,
        "Uses the device biometric prompt. No biometric data is read by oPay."));
    page.addView(toggles);

    section(page, "PROVIDERS");
    LinearLayout providerCard = Ui.card(activity);
    TextView diagnostics = Ui.mono(activity, Providers.diagnostics(activity), 8.5f, Ui.MUTED);
    diagnostics.setTextIsSelectable(true);
    providerCard.addView(diagnostics);
    page.addView(providerCard);
    page.addView(Ui.label(activity, "Installed is not the same as able to accept an external"
        + " payment. Only handlers advertising upi://pay are listed on the Pay screen; the"
        + " generic chooser is always available.", 8.5f, Ui.MUTED));

    section(page, "DATA");
    LinearLayout links = Ui.row(activity);
    links.addView(Ui.button(activity, "BENEFICIARIES", () -> show("beneficiaries")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    links.addView(Ui.button(activity, "ACCOUNTS", () -> show("accounts")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    links.addView(Ui.button(activity, "IMPORT", () -> show("import")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    page.addView(links);

    LinearLayout exports = Ui.row(activity);
    exports.addView(Ui.button(activity, "EXPORT CSV", () -> exportAll("CSV")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    exports.addView(Ui.button(activity, "EXPORT JSON", () -> exportAll("JSON")),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    page.addView(exports);
    LinearLayout backupRow = Ui.row(activity);
    backupRow.addView(Ui.button(activity, "ENCRYPTED BACKUP", this::createBackup),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    backupRow.addView(Ui.button(activity, "RESTORE", this::pickRestore),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1f));
    page.addView(backupRow);
    page.addView(Ui.button(activity, "DELETE ALL LOCAL FINANCIAL DATA", this::confirmWipe),
        Ui.full(36, activity));

    section(page, "ABOUT");
    page.addView(Ui.label(activity, "oPay is not a payment provider, TPAP or bank. Payments are"
        + " handed to the UPI app you choose; settlements must be verified in your bank"
        + " statement. oPay has no network permission use, telemetry, ads or cloud sync."
        + " Notification-based capture is not included because a downloaded DEX module cannot"
        + " register a NotificationListenerService.", 9, Ui.MUTED));
    page.addView(Ui.label(activity, constructVersion(), 8.5f, Ui.MUTED));
    return page;
  }

  private String constructVersion() {
    try {
      return "Ledger records: " + store.count() + " \u00b7 module mod12 \u00b7 schema 1";
    } catch (RuntimeException error) {
      return "Ledger unavailable: " + message(error);
    }
  }

  private View toggle(String key, String title, boolean fallback, String note) {
    LinearLayout block = Ui.column(activity);
    boolean value = prefs.getBoolean(key, fallback);
    Button button = Ui.button(activity, (value ? "\u25a0 " : "\u25a1 ") + title + "  "
        + (value ? "ON" : "OFF"), () -> {
      boolean next = !prefs.getBoolean(key, fallback);
      if (key.equals("lock") && next && !Biometrics.available(activity)) {
        setStatus("No biometric sensor is enrolled on this device.", Ui.RED);
        return;
      }
      if (key.equals("gps") && next && !LocationFix.permitted(activity)) {
        prefs.edit().putBoolean(key, false).apply();
        activity.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        show("settings");
        return;
      }
      prefs.edit().putBoolean(key, next).apply();
      show("settings");
      setStatus(title + (next ? " on" : " off"), Ui.CYAN);
    });
    button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    block.addView(button, Ui.full(34, activity));
    block.addView(Ui.label(activity, note, 8.5f, Ui.MUTED));
    return block;
  }

  private void toggleLock() {
    boolean next = !prefs.getBoolean("lock", false);
    if (next && !Biometrics.available(activity)) {
      setStatus("Enrol a biometric or set a screen lock first.", Ui.RED);
      return;
    }
    prefs.edit().putBoolean("lock", next).apply();
    setStatus(next ? "Biometric lock enabled" : "Biometric lock disabled", Ui.CYAN);
    if (lockButton != null) lockButton.setText(next ? "\u25a0 LOCK ON" : "\u25a1 LOCK");
  }

  private void showLock() {
    body.removeAllViews();
    LinearLayout card = Ui.card(activity);
    card.addView(Ui.label(activity, "oPay is locked", 16, Ui.CYAN));
    card.addView(Ui.label(activity, "Your financial ledger stays encrypted in app-private"
        + " storage. Unlock with the device biometric prompt.", 10, Ui.MUTED));
    card.addView(Ui.accent(activity, "UNLOCK", () -> Biometrics.authenticate(activity,
        () -> { locked = false; show("home"); setStatus("Unlocked", Ui.GREEN); },
        () -> setStatus("oPay stays locked", Ui.RED))), Ui.full(44, activity));
    card.addView(Ui.label(activity, "Closing oPay locks it again.", 9, Ui.MUTED));
    body.addView(card);
  }

  // ------------------------------------------------------------------ scanner

  private void openScanner() {
    if (activity.checkSelfPermission(android.Manifest.permission.CAMERA)
        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
      setStatus("Allow camera access to scan a UPI QR.", Ui.AMBER);
      return;
    }
    overlay.removeAllViews();
    overlay.setVisibility(View.VISIBLE);
    scanner = new QrScannerView(activity);
    overlay.addView(scanner, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout actions = Ui.row(activity);
    actions.setBackgroundColor(0xcc000000);
    actions.addView(Ui.button(activity, "CLOSE", this::closeOverlay),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 42), 1f));
    actions.addView(Ui.button(activity, "GALLERY", this::pickGalleryQr),
        new LinearLayout.LayoutParams(0, Ui.dp(activity, 42), 1f));
    FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(-1, Ui.dp(activity, 46));
    actionParams.gravity = Gravity.BOTTOM;
    overlay.addView(actions, actionParams);
    scanner.setListener(new QrScannerView.Listener() {
      @Override public void onQr(String text) {
        closeOverlay();
        acceptQr(text);
      }

      @Override public void onError(String message) {
        hostLog("camera-failed", message);
        closeOverlay();
        setStatus(message, Ui.RED);
      }

      @Override public void onStarted() {
        setStatus("Scanning for a UPI QR\u2026", Ui.CYAN);
      }
    });
    scanner.start();
  }

  private void closeOverlay() {
    if (scanner != null) {
      scanner.stop();
      scanner = null;
    }
    if (overlay != null) {
      overlay.removeAllViews();
      overlay.setVisibility(View.GONE);
    }
  }

  private void pickGalleryQr() {
    closeOverlay();
    try {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
      activity.startActivityForResult(intent, REQ_QR_GALLERY);
    } catch (RuntimeException error) {
      hostLog("gallery-picker-failed", error);
      setStatus("No image picker available: " + message(error), Ui.RED);
    }
  }

  private void handleGalleryQr(int result, Intent data) {
    if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
    final Uri uri = data.getData();
    work("qr-image-failed", () -> {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 2;
      Bitmap bitmap;
      try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
        bitmap = BitmapFactory.decodeStream(input, null, options);
      }
      if (bitmap == null) throw new IllegalArgumentException("Image could not be decoded");
      String text = QrTools.decode(bitmap);
      final String value = text;
      ui(() -> {
        if (value == null) {
          setStatus("No QR code found in that image.", Ui.RED);
          hostLog("qr-image-empty", "no QR decoded");
        } else acceptQr(value);
      });
    });
  }

  private void acceptQr(String raw) {
    try {
      Upi.Request request = Upi.parse(raw);
      draft = request;
      show("pay");
      setStatus("QR loaded \u00b7 review the beneficiary and amount, then PAY", Ui.GREEN);
    } catch (IllegalArgumentException error) {
      hostLog("qr-invalid", error);
      setStatus("Not a valid UPI payment QR: " + message(error), Ui.RED);
    }
  }

  // ------------------------------------------------------------------ export & backup

  private void requestExport(String mime, String name, byte[] payload, int requestCode) {
    exportPayload = payload;
    exportMime = mime;
    exportName = name;
    try {
      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
          .putExtra(Intent.EXTRA_TITLE, name);
      activity.startActivityForResult(intent, requestCode);
    } catch (RuntimeException error) {
      hostLog("export-picker-failed", error);
      setStatus("No save picker available: " + message(error), Ui.RED);
    }
  }

  private void requestExport(String mime, String name, byte[] payload) {
    requestExport(mime, name, payload, REQ_EXPORT);
  }

  private void handleExportWrite(int result, Intent data) {
    if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
    if (exportPayload == null) return;
    final Uri uri = data.getData();
    final byte[] payload = exportPayload;
    work("export-write-failed", () -> {
      try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
        if (out == null) throw new IllegalStateException("The picker returned no file");
        out.write(payload);
        out.flush();
      }
      ui(() -> setStatus("Saved " + exportName, Ui.GREEN));
    });
  }

  private void exportAll(String kind) {
    work("export-build-failed", () -> {
      List<LedgerTransaction> rows = store.range(0, Long.MAX_VALUE / 4);
      byte[] payload;
      String mime;
      String name = "opay-ledger-" + new SimpleDateFormat("yyyyMMdd", Locale.US)
          .format(new Date()) + (kind.equals("JSON") ? ".json" : ".csv");
      if (kind.equals("JSON")) {
        payload = Exporter.json(rows).getBytes(StandardCharsets.UTF_8);
        mime = "application/json";
      } else {
        payload = Exporter.csv(rows).getBytes(StandardCharsets.UTF_8);
        mime = "text/csv";
      }
      final byte[] bytes = payload;
      final String type = mime;
      ui(() -> requestExport(type, name, bytes));
    });
  }

  private void createBackup() {
    EditText passphrase = Ui.input(activity, "Backup passphrase (at least 8 characters)");
    passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AlertDialog.Builder(activity).setTitle("Encrypted backup")
        .setMessage("The backup is AES-256-GCM encrypted. Without this passphrase it cannot be"
            + " restored. It is never stored by oPay.")
        .setView(passphrase)
        .setPositiveButton("CREATE", (dialog, which) -> {
          final String secret = passphrase.getText().toString();
          if (secret.length() < 8) {
            setStatus("Passphrase must be at least 8 characters.", Ui.RED);
            return;
          }
          work("backup-failed", () -> {
            byte[] json = backupJson().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = BackupCrypto.encrypt(json, secret.toCharArray());
            final byte[] payload = encrypted;
            String name = "opay-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                .format(new Date()) + ".opay";
            ui(() -> requestExport("application/octet-stream", name, payload, REQ_BACKUP));
          });
        })
        .setNegativeButton("Cancel", null).show();
  }

  private String backupJson() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("format", "opay-backup");
    root.put("version", 1);
    root.put("createdAt", System.currentTimeMillis());
    List<Object> accounts = new ArrayList<>();
    for (OpayStore.Account account : store.accounts()) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", account.id);
      map.put("name", account.name);
      map.put("bank", account.bank);
      map.put("masked", account.masked);
      map.put("upi", account.upi);
      map.put("createdAt", account.createdAt);
      accounts.add(map);
    }
    List<Object> beneficiaries = new ArrayList<>();
    for (OpayStore.Beneficiary beneficiary : store.beneficiaries("")) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", beneficiary.id);
      map.put("name", beneficiary.name);
      map.put("upi", beneficiary.upi);
      map.put("note", beneficiary.note);
      map.put("lastUsed", beneficiary.lastUsed);
      map.put("uses", beneficiary.uses);
      beneficiaries.add(map);
    }
    List<Object> transactions = new ArrayList<>();
    for (LedgerTransaction transaction : store.range(0, Long.MAX_VALUE / 4))
      transactions.add(transaction.toMap());
    root.put("accounts", accounts);
    root.put("beneficiaries", beneficiaries);
    root.put("transactions", transactions);
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("upi", prefs.getString("upi", ""));
    settings.put("upiName", prefs.getString("upiName", ""));
    root.put("settings", settings);
    return in.otrail.extensions.opay.core.Json.write(root);
  }

  private void pickRestore() {
    try {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      activity.startActivityForResult(intent, REQ_RESTORE);
    } catch (RuntimeException error) {
      hostLog("restore-picker-failed", error);
      setStatus("No document picker available: " + message(error), Ui.RED);
    }
  }

  private void handleRestoreRead(int result, Intent data) {
    if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
    final Uri uri = data.getData();
    setStatus("Reading backup\u2026", Ui.MUTED);
    work("restore-read-failed", () -> {
      byte[] encrypted = readAll(uri, 64 * 1024 * 1024);
      ui(() -> promptRestore(encrypted));
    });
  }

  private void promptRestore(byte[] encrypted) {
    EditText passphrase = Ui.input(activity, "Backup passphrase");
    passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AlertDialog.Builder(activity).setTitle("Restore encrypted backup")
        .setView(passphrase)
        .setPositiveButton("RESTORE", (dialog, which) -> {
          final String secret = passphrase.getText().toString();
          work("restore-failed", () -> {
            byte[] plain = BackupCrypto.decrypt(encrypted, secret.toCharArray());
            Map<String, Object> root = in.otrail.extensions.opay.core.Json.object(
                in.otrail.extensions.opay.core.Json.parse(new String(plain, StandardCharsets.UTF_8)));
            Object rawTransactions = root.get("transactions");
            List<LedgerTransaction> rows = new ArrayList<>();
            if (rawTransactions instanceof List) {
              for (Object item : in.otrail.extensions.opay.core.Json.array(rawTransactions)) {
                if (item instanceof Map)
                  rows.add(LedgerTransaction.fromMap(in.otrail.extensions.opay.core.Json.object(item)));
              }
            }
            Object rawAccounts = root.get("accounts");
            if (rawAccounts instanceof List) {
              for (Object item : in.otrail.extensions.opay.core.Json.array(rawAccounts)) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> map = in.otrail.extensions.opay.core.Json.object(item);
                OpayStore.Account account = new OpayStore.Account();
                account.id = String.valueOf(map.get("id"));
                account.name = String.valueOf(map.get("name"));
                account.bank = String.valueOf(map.get("bank"));
                account.masked = String.valueOf(map.get("masked"));
                account.upi = String.valueOf(map.get("upi"));
                store.saveAccount(account);
              }
            }
            Object rawBeneficiaries = root.get("beneficiaries");
            if (rawBeneficiaries instanceof List) {
              for (Object item : in.otrail.extensions.opay.core.Json.array(rawBeneficiaries)) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> map = in.otrail.extensions.opay.core.Json.object(item);
                OpayStore.Beneficiary beneficiary = new OpayStore.Beneficiary();
                beneficiary.id = String.valueOf(map.get("id"));
                beneficiary.name = String.valueOf(map.get("name"));
                beneficiary.upi = String.valueOf(map.get("upi"));
                beneficiary.note = String.valueOf(map.get("note"));
                store.saveBeneficiary(beneficiary);
              }
            }
            int inserted = store.insertBatch(rows, "restore-" + UUID.randomUUID());
            Object rawSettings = root.get("settings");
            if (rawSettings instanceof Map) {
              Map<String, Object> settings = in.otrail.extensions.opay.core.Json.object(rawSettings);
              Object upi = settings.get("upi");
              if (upi != null) prefs.edit().putString("upi", String.valueOf(upi)).apply();
            }
            ui(() -> {
              setStatus(inserted + " transactions restored \u00b7 duplicates skipped", Ui.GREEN);
              show("ledger");
            });
          });
        })
        .setNegativeButton("Cancel", null).show();
  }

  private void confirmWipe() {
    new AlertDialog.Builder(activity).setTitle("Delete all local financial data?")
        .setMessage("Removes the ledger, accounts and beneficiaries from this phone. Exported"
            + " files and bank records are unaffected. This cannot be undone.")
        .setPositiveButton("DELETE", (dialog, which) -> work("wipe-failed", () -> {
          store.deleteAll();
          ui(() -> {
            lastDetail = null;
            reportSummary = null;
            setStatus("Local financial data deleted", Ui.MUTED);
            show("home");
          });
        }))
        .setNegativeButton("Cancel", null).show();
  }

  // ------------------------------------------------------------------ helpers

  private interface Job {
    void run() throws Exception;
  }

  private void work(String action, Job task) {
    if (worker == null || worker.isShutdown()) return;
    worker.submit(() -> {
      try {
        task.run();
      } catch (Throwable error) {
        hostLog(action, error);
        ui(() -> setStatus(action.replace('-', ' ') + ": " + message(error), Ui.RED));
      }
    });
  }

  private void ui(Runnable action) {
    main.post(() -> {
      if (activity == null) return;
      try {
        action.run();
      } catch (RuntimeException error) {
        hostLog("ui-failed", error);
      }
    });
  }

  private byte[] readAll(Uri uri, int limit) throws java.io.IOException {
    try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
      if (input == null) throw new java.io.IOException("The picker returned no stream");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read, total = 0;
      while ((read = input.read(buffer)) > 0) {
        total += read;
        if (total > limit) throw new java.io.IOException("File is larger than the import limit");
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private String displayName(Uri uri) {
    try (android.database.Cursor cursor = activity.getContentResolver().query(uri,
        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        if (name != null && !name.isEmpty()) return name;
      }
    } catch (RuntimeException ignored) {
      // Fall back to the last path segment.
    }
    String path = uri.getLastPathSegment();
    return path == null ? "statement" : path;
  }

  private void copy(String text) {
    try {
      ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("oPay", text));
    } catch (RuntimeException error) {
      hostLog("clipboard-failed", error);
    }
  }

  private static String clip(String text, int limit) {
    if (text == null) return "";
    String value = text.replace('\n', ' ');
    return value.length() <= limit ? value : value.substring(0, Math.max(0, limit - 1)) + "\u2026";
  }

  private static String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
  }

  private void hostLog(String action, Throwable error) {
    hostLog(action, error.getClass().getSimpleName()
        + (error.getMessage() == null ? "" : ": " + error.getMessage()));
  }

  /** Every failure is forwarded to the LOGGER module through the host event log. */
  private void hostLog(String action, String detail) {
    Host owner = host;
    if (owner == null) return;
    try {
      owner.log(action, detail);
    } catch (Throwable ignored) {
      // Logging must never break the payment flow.
    }
  }
}

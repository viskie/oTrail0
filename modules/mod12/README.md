# oPay — UPI payments, ledger, imports and reports (module `mod12`)

oPay is an offline-first personal finance module for oTraiL. It prepares standard
`upi://pay` payments and hands them to an installed UPI app. It is **not** a payment
provider, TPAP, bank or gateway: it holds no money, has no NPCI API access, no remote
server and no telemetry. Every financial record stays in the app-private SQLite
database under `files/modules/data/mod12/`.

Requires host **0.4.41 (64)** for the UPI package-visibility queries and the
`USE_BIOMETRIC` permission. Minimum Android 10 (SDK 30).

## What it does

- **Pay** — validates a UPI ID/amount, saves a pending ledger row first, then opens
  PhonePe, JioFinance, Paytm, Google Pay, BHIM or the generic chooser. The provider
  owns account selection, amount confirmation and the UPI PIN. oPay never asks for
  or stores a PIN/OTP and never automates confirmation.
- **QR** — live Camera2 scanner, gallery image import and clipboard/manual entry.
  Scanning only fills the payment form; nothing is paid automatically. Personal and
  fixed-amount receiving QRs are generated locally (ZXing core encoder).
- **GPS snapshot** — each payment started in oPay stores the available fix
  (latitude, longitude, accuracy, provider, fix time) with the pending transaction,
  shown in transaction details. Disable it in MORE. If location is not allowed the
  payment still proceeds and the row simply has no fix.
- **Ledger** — execution and reconciliation states are separate. `PROVIDER SAYS OK`
  is never treated as settled money. Pending attempts survive process death and are
  recovered on the dashboard ("payment result not received").
- **Import** — CSV, XLSX, PDF with embedded text, JSON, pasted bank/payment SMS and
  user-selected receipt text. Automatic column mapping with a manual override.
  Synthetic fixtures live in `fixtures/`.
- **Reconcile** — UTR/RRN and provider references match strongly; amount/date/
  counterparty scores are suggestions only and ambiguous matches require a choice.
  Re-importing a statement is idempotent. Refunds, reversals and self-transfers are
  kept out of income/expenditure totals.
- **Reports** — day, week, month, quarter, Indian financial year (1 April–31 March),
  calendar year and custom ranges: credits, debits, net, count, average, largest,
  category/provider/account/beneficiary breakdowns, pending/unreconciled, refunds,
  reversals and internal transfers, plus local bar charts. CSV, JSON and PDF export.
- **Security** — local-only SQLite, parameterized queries, FLAG_SECURE screenshot
  protection, optional biometric lock, redacted producer logs, and AES-256-GCM
  backups with a user passphrase (PBKDF2-HMAC-SHA256, 120k iterations). Statement
  passwords and backup passphrases are never persisted. No `READ_SMS` requirement.

## Provider support

Installed applications are discovered at runtime through package-visibility
queries, and only apps that advertise a `upi://pay` handler are offered. An app can
be installed and still refuse external payment initiation; oPay then shows the
provider's error and records the attempt as failed/unknown. The generic chooser is
always available. Package identifiers are verified at runtime, not assumed.

## Import limits (explicit)

- PDF: only digitally generated files with embedded text; Flate or uncompressed
  content streams, `Tj`/`TJ` operators. Scanned/image-only PDFs are rejected with a
  message, not imported as empty data. Encrypted/password PDFs are rejected because
  no local decryption library ships in the module.
- XLSX: first worksheet, shared strings and inline strings, no formulas/charts.
- CSV/TSV: delimiter auto-detection, quoted fields, automatic header detection.
- PhonePe/JioFinance/Paytm: no public transaction-history API exists and none is
  used. Their exports are imported as generic tables with provider detection; there
  is no claim of a provider-specific format that has not been verified against a
  real sample. Use the manual column mapping for unfamiliar exports.
- Notification capture is not implemented: a downloaded DEX module cannot register
  a `NotificationListenerService`, and Android does not retain historical
  notifications anyway.
- Share-sheet receipt targets are not registered for the same manifest reason; use
  "Paste SMS" for receipt text.

## Device testing (no real payments in automated tests)

1. Install host 0.4.41, refresh modules, install and activate OPAY.
2. Settings → extension modules: confirm the module opens and MORE lists installed
   UPI handlers with their package names.
3. Grant camera and location when prompted; scan a real merchant QR (or a test QR)
   and verify the form is pre-filled but **not** paid automatically.
4. Pay ₹1 to a known UPI ID with PhonePe, then repeat with JioFinance and Paytm.
   Verify each app asks for its own PIN, and check the ledger row: provider response,
   execution state and the GPS snapshot.
5. Force-stop oTraiL while the UPI app is open. Reopen oPay: the pending banner must
   appear and the row must not be marked settled.
6. Export a PhonePe/Jio/Paytm statement, import it, check the preview and mapping,
   commit, then import the same file again: no new rows.
7. Reconcile a pending payment against the imported row using UTR/RRN; try two
   similar amounts to confirm the ambiguous-match review.
8. Open Reports for the month and FY, check totals against the statement, then
   export CSV/JSON/PDF and restore an encrypted backup on a second install.

Automated tests do not initiate financial transactions; they use the synthetic
fixtures only. No Android device was attached while this version was built, so the
camera preview, runtime permission dialogs, provider return values and biometric
prompt remain device-unverified.

## Build

```
ANDROID_HOME=/path/to/sdk JAVA_HOME=/path/to/jdk17 \
  python3 scripts/build-module.py opay 1.0.0
```

`libs/zxing-core-3.5.3.jar` (Apache-2.0, see `libs/ZXING-LICENSE.txt`) is linked
into the module DEX by `build-module.py`. The host only needs the manifest queries
and permissions; no ZXing class is loaded by the host.

## Known limitations

- A successful Android activity result, or a provider's success string, is not
  settlement. Only a statement import or explicit user confirmation changes the
  reconciliation state.
- The in-app "provider says OK" state can be spoofed by a malicious app that handles
  `upi://pay`; it is display-only and never used for totals.
- Internal transfers are recognised only when categorised/flagged by the user;
  the generic importers cannot always tell a self-transfer from a payment.
- Balance is not shown: without a verified opening balance and complete reconciled
  coverage, a ledger net is not a bank balance.
- Imports are capped at 32 MB; large PDFs are limited by memory during extraction.
- No cloud sync, notifications, OCR, or automatic payment retry, by design.

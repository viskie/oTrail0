package in.otrail.extensions.opay;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import in.otrail.extensions.opay.core.Upi;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Registry of UPI applications and the standard intent used to hand a payment over.
 * Nothing here calls a provider-internal activity, and no provider is assumed to
 * support external payment initiation just because it is installed.
 */
public final class Providers {
  private Providers() {}

  public static final class Provider {
    public final String name;
    public final String packageName;

    Provider(String name, String packageName) {
      this.name = name;
      this.packageName = packageName;
    }

    public String label() { return name; }
  }

  public static final Provider[] KNOWN = {
      new Provider("PhonePe", "com.phonepe.app"),
      new Provider("JioFinance", "in.jfs.jiofinance"),
      new Provider("Paytm", "net.one97.paytm"),
      new Provider("Google Pay", "com.google.android.apps.nbu.paisa.user"),
      new Provider("BHIM", "in.org.npci.upiapp")
  };

  private static final String[] RESULT_KEYS = {
      "Status", "status", "responseCode", "response", "txnId", "transactionId", "Transaction ID",
      "approvalRefNo", "approvalRef", "UPI_TID", "txnRef", "rrn", "message", "error"
  };

  public static Intent payIntent(Upi.Request request, String packageName) {
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(request.toUri()));
    intent.addCategory(Intent.CATEGORY_BROWSABLE);
    if (packageName != null && !packageName.isEmpty()) intent.setPackage(packageName);
    return intent;
  }

  /** Apps that actually advertise a handler for upi://pay on this device. */
  public static List<Provider> installed(Activity activity) {
    Set<String> handlers = new LinkedHashSet<>();
    try {
      Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"));
      for (ResolveInfo info : activity.getPackageManager()
          .queryIntentActivities(probe, PackageManager.MATCH_ALL)) {
        if (info.activityInfo != null && info.activityInfo.packageName != null)
          handlers.add(info.activityInfo.packageName);
      }
    } catch (RuntimeException ignored) {
      // Package visibility can hide handlers; the generic chooser still works.
    }
    List<Provider> providers = new ArrayList<>();
    for (Provider provider : KNOWN)
      if (handlers.contains(provider.packageName)) providers.add(provider);
    for (String handler : handlers) {
      boolean known = false;
      for (Provider provider : KNOWN)
        if (provider.packageName.equals(handler)) known = true;
      if (!known) providers.add(new Provider(appLabel(activity, handler), handler));
    }
    return providers;
  }

  public static boolean isInstalled(Activity activity, String packageName) {
    try {
      activity.getPackageManager().getPackageInfo(packageName, 0);
      return true;
    } catch (PackageManager.NameNotFoundException missing) {
      return false;
    }
  }

  private static String appLabel(Activity activity, String packageName) {
    try {
      PackageManager manager = activity.getPackageManager();
      CharSequence label = manager.getApplicationLabel(
          manager.getApplicationInfo(packageName, 0));
      return label == null ? packageName : label.toString();
    } catch (Exception ignored) {
      return packageName;
    }
  }

  /** Diagnostics shown in Settings: installed registry apps and generic handlers. */
  public static String diagnostics(Activity activity) {
    List<Provider> installed = installed(activity);
    StringBuilder text = new StringBuilder();
    for (Provider provider : KNOWN) {
      text.append(installed(activity).contains(provider) ? "\u2713 " : "\u00b7 ")
          .append(provider.name).append("  ").append(provider.packageName).append('\n');
      if (!isInstalled(activity, provider.packageName))
        text.append("   not installed or no upi://pay handler\n");
      else if (!installed.contains(provider))
        text.append("   installed, but does not advertise external upi://pay\n");
    }
    int extra = 0;
    for (Provider provider : installed) {
      boolean known = false;
      for (Provider candidate : KNOWN) if (candidate.packageName.equals(provider.packageName)) known = true;
      if (!known) {
        if (extra == 0) text.append("Other handlers:\n");
        text.append("  \u00b7 ").append(provider.name).append("  ")
            .append(provider.packageName).append('\n');
        extra++;
      }
    }
    text.append("\nThe generic chooser is always available.");
    return text.toString();
  }

  /** Extracts every provider response field without deciding that money moved. */
  public static String responseText(int resultCode, Intent data) {
    if (data == null) return "Provider returned no details (result " + resultCode + ")";
    StringBuilder text = new StringBuilder("Android result: ")
        .append(resultCode == Activity.RESULT_OK ? "RESULT_OK" : "code " + resultCode);
    Bundle extras = data.getExtras();
    Set<String> seen = new LinkedHashSet<>();
    if (extras != null) {
      for (String key : RESULT_KEYS) {
        Object value = extras.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) continue;
        seen.add(key);
        text.append('\n').append(key).append(": ").append(value);
      }
    }
    String raw = data.getDataString();
    if (raw != null && !raw.isEmpty()) text.append("\nData: ").append(raw);
    if (seen.isEmpty()) text.append("\nNo documented status fields were returned.");
    return text.toString();
  }

  /**
   * Only an explicit success status in the provider response is treated as a provider
   * report. RESULT_OK alone never means the payment settled.
   */
  public static boolean reportedSuccess(Intent data) {
    if (data == null || data.getExtras() == null) return false;
    Bundle extras = data.getExtras();
    String status = value(extras, "Status", "status", "responseCode", "response");
    if (status == null) return false;
    String normalized = status.trim().toUpperCase(Locale.US);
    return normalized.equals("SUCCESS") || normalized.equals("00") || normalized.equals("0")
        || normalized.equals("APPROVED") || normalized.equals("COMPLETED");
  }

  public static boolean reportedFailure(Intent data) {
    if (data == null || data.getExtras() == null) return false;
    Bundle extras = data.getExtras();
    String status = value(extras, "Status", "status", "responseCode", "response", "error");
    if (status == null) return false;
    String normalized = status.trim().toUpperCase(Locale.US);
    return normalized.equals("FAILURE") || normalized.equals("FAILED") || normalized.equals("DECLINED")
        || normalized.equals("CANCELLED") || normalized.equals("CANCELED")
        || normalized.startsWith("ERR") || normalized.equals("-1");
  }

  private static String value(Bundle extras, String... keys) {
    for (String key : keys) {
      Object value = extras.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) return String.valueOf(value);
    }
    return null;
  }

  public static String providerNameForPackage(String packageName) {
    for (Provider provider : KNOWN)
      if (provider.packageName.equals(packageName)) return provider.name;
    return packageName;
  }
}

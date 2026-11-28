package in.otrail.extensions.pop3;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Account settings in private preferences and the offline inbox on disk. */
final class Pop3Store {
  private static final String PREFS = "pop3-client";
  private static final int MAX_CACHED = 400;
  private final SharedPreferences prefs;
  private final File dir;

  Pop3Store(Context context, File privateDirectory) {
    prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    dir = new File(privateDirectory, "pop3");
    //noinspection ResultOfMethodCallIgnored
    dir.mkdirs();
  }

  synchronized List<Pop3Account> accounts() {
    List<Pop3Account> out = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(prefs.getString("accounts", "[]"));
      for (int i = 0; i < array.length(); i++) {
        JSONObject o = array.optJSONObject(i);
        if (o != null) out.add(Pop3Account.from(o));
      }
    } catch (Exception ignored) {
      // A corrupt store falls back to no accounts.
    }
    return out;
  }

  synchronized void saveAccounts(List<Pop3Account> accounts) {
    JSONArray array = new JSONArray();
    for (Pop3Account account : accounts) array.put(account.toJson());
    prefs.edit().putString("accounts", array.toString()).apply();
  }

  synchronized void deleteAccount(String id) {
    List<Pop3Account> accounts = accounts();
    List<Pop3Account> keep = new ArrayList<>();
    for (Pop3Account account : accounts) if (!account.id.equals(id)) keep.add(account);
    saveAccounts(keep);
    //noinspection ResultOfMethodCallIgnored
    file(id).delete();
    prefs.edit().remove(lastSyncKey(id)).apply();
  }

  static String newId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  synchronized List<Pop3Message> messages(String accountId) {
    List<Pop3Message> out = new ArrayList<>();
    File file = file(accountId);
    if (!file.isFile()) return out;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        new FileInputStream(file), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.trim().isEmpty()) continue;
        try {
          Pop3Message message = Pop3Message.from(new JSONObject(line));
          if (message.uid != null && !message.uid.isEmpty()) out.add(message);
        } catch (Exception ignored) {
          // Skip a damaged cache row instead of dropping the whole inbox.
        }
      }
    } catch (Exception ignored) {
      // An unreadable cache behaves like an empty one; the next sync rewrites it.
    }
    return out;
  }

  synchronized void saveMessages(String accountId, List<Pop3Message> messages) {
    List<Pop3Message> sorted = new ArrayList<>(messages);
    sorted.sort(Comparator
        .comparingLong((Pop3Message m) -> m.time).reversed()
        .thenComparing(m -> m.uid));
    if (sorted.size() > MAX_CACHED) sorted = new ArrayList<>(sorted.subList(0, MAX_CACHED));
    StringBuilder out = new StringBuilder();
    for (Pop3Message message : sorted) out.append(message.toJson().toString()).append('\n');
    File temp = new File(dir, file(accountId).getName() + ".tmp");
    try (OutputStreamWriter writer = new OutputStreamWriter(
        new FileOutputStream(temp), StandardCharsets.UTF_8)) {
      writer.write(out.toString());
    } catch (Exception ignored) {
      //noinspection ResultOfMethodCallIgnored
      temp.delete();
      return;
    }
    File target = file(accountId);
    if (!temp.renameTo(target)) {
      //noinspection ResultOfMethodCallIgnored
      temp.delete();
    }
  }

  synchronized long lastSync(String accountId) {
    return prefs.getLong(lastSyncKey(accountId), 0);
  }

  synchronized void setLastSync(String accountId, long millis) {
    prefs.edit().putLong(lastSyncKey(accountId), millis).apply();
  }

  private File file(String accountId) {
    return new File(dir, "inbox-" + accountId.replaceAll("[^A-Za-z0-9_-]", "_") + ".jsonl");
  }

  private static String lastSyncKey(String accountId) {
    return "lastSync-" + accountId;
  }
}

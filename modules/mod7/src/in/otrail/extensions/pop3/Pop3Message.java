package in.otrail.extensions.pop3;

import org.json.JSONException;
import org.json.JSONObject;

/** A message kept in the offline inbox. */
final class Pop3Message {
  String accountId = "";
  String uid = "";
  String from = "";
  String subject = "";
  String date = "";
  String body = "";
  long time;
  long size;
  boolean seen;

  String displaySubject() {
    return subject == null || subject.trim().isEmpty() ? "(no subject)" : subject.trim();
  }

  String snippet() {
    String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
    return text.length() <= 160 ? text : text.substring(0, 157) + "…";
  }

  JSONObject toJson() {
    JSONObject o = new JSONObject();
    try {
      o.put("account", accountId);
      o.put("uid", uid);
      o.put("from", from);
      o.put("subject", subject);
      o.put("date", date);
      o.put("body", body);
      o.put("time", time);
      o.put("size", size);
      o.put("seen", seen);
    } catch (JSONException ignored) {
      // Plain string/number values only.
    }
    return o;
  }

  static Pop3Message from(JSONObject o) {
    Pop3Message m = new Pop3Message();
    m.accountId = o.optString("account");
    m.uid = o.optString("uid");
    m.from = o.optString("from");
    m.subject = o.optString("subject");
    m.date = o.optString("date");
    m.body = o.optString("body");
    m.time = o.optLong("time");
    m.size = o.optLong("size");
    m.seen = o.optBoolean("seen");
    return m;
  }
}

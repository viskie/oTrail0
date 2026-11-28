package in.otrail.extensions.pop3;

import org.json.JSONException;
import org.json.JSONObject;

/** One configurable POP3 mailbox. Passwords live only in the app's private storage. */
final class Pop3Account {
  String id = "";
  String name = "";
  String host = "";
  int port = 995;
  String user = "";
  String password = "";
  boolean tls = true;

  String label() {
    if (name != null && !name.trim().isEmpty()) return name.trim();
    return user == null || user.isEmpty() ? host : user;
  }

  String endpoint() {
    return user + "@" + host + ":" + port + (tls ? " · TLS" : " · plain");
  }

  JSONObject toJson() {
    JSONObject o = new JSONObject();
    try {
      o.put("id", id);
      o.put("name", name);
      o.put("host", host);
      o.put("port", port);
      o.put("user", user);
      o.put("password", password);
      o.put("tls", tls);
    } catch (JSONException ignored) {
      // Keys and values here are always JSON-safe.
    }
    return o;
  }

  static Pop3Account from(JSONObject o) {
    Pop3Account a = new Pop3Account();
    a.id = o.optString("id");
    a.name = o.optString("name");
    a.host = o.optString("host");
    a.port = o.optInt("port", 995);
    a.user = o.optString("user");
    a.password = o.optString("password");
    a.tls = o.optBoolean("tls", true);
    return a;
  }
}

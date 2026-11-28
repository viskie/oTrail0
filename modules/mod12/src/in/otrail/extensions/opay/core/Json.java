package in.otrail.extensions.opay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer. The module keeps its own implementation so the core
 * parsers and ledger model can be unit-tested on a plain JVM without org.json.
 */
public final class Json {
  private Json() {}

  public static Object parse(String text) {
    if (text == null) throw new IllegalArgumentException("Empty JSON");
    Reader reader = new Reader(text);
    Object value = reader.value();
    reader.skip();
    if (!reader.done()) throw new IllegalArgumentException("Trailing JSON content");
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object value) {
    if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> array(Object value) {
    if (!(value instanceof List)) throw new IllegalArgumentException("Expected a JSON array");
    return (List<Object>) value;
  }

  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String) {
      quote((String) value, out);
    } else if (value instanceof Boolean || value instanceof Number) {
      out.append(value);
    } else if (value instanceof Map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (!first) out.append(',');
        first = false;
        quote(String.valueOf(entry.getKey()), out);
        out.append(':');
        write(entry.getValue(), out);
      }
      out.append('}');
    } else if (value instanceof Iterable) {
      out.append('[');
      boolean first = true;
      for (Object item : (Iterable<?>) value) {
        if (!first) out.append(',');
        first = false;
        write(item, out);
      }
      out.append(']');
    } else {
      quote(String.valueOf(value), out);
    }
  }

  private static void quote(String value, StringBuilder out) {
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"': out.append("\\\""); break;
        case '\\': out.append("\\\\"); break;
        case '\n': out.append("\\n"); break;
        case '\r': out.append("\\r"); break;
        case '\t': out.append("\\t"); break;
        default:
          if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
          else out.append(character);
      }
    }
    out.append('"');
  }

  private static final class Reader {
    private final String text;
    private int at;

    Reader(String text) { this.text = text; }

    boolean done() { return at >= text.length(); }

    void skip() {
      while (!done() && Character.isWhitespace(text.charAt(at))) at++;
    }

    Object value() {
      skip();
      if (done()) throw new IllegalArgumentException("Unexpected end of JSON");
      char character = text.charAt(at);
      if (character == '{') return object();
      if (character == '[') return list();
      if (character == '"') return string();
      if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
      if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
      if (text.startsWith("null", at)) { at += 4; return null; }
      return number();
    }

    Map<String, Object> object() {
      Map<String, Object> map = new LinkedHashMap<>();
      at++;
      skip();
      if (!done() && text.charAt(at) == '}') { at++; return map; }
      while (true) {
        skip();
        String key = string();
        skip();
        expect(':');
        map.put(key, value());
        skip();
        if (done()) throw new IllegalArgumentException("Unterminated JSON object");
        char next = text.charAt(at++);
        if (next == '}') return map;
        if (next != ',') throw new IllegalArgumentException("Expected , or }");
      }
    }

    List<Object> list() {
      List<Object> list = new ArrayList<>();
      at++;
      skip();
      if (!done() && text.charAt(at) == ']') { at++; return list; }
      while (true) {
        list.add(value());
        skip();
        if (done()) throw new IllegalArgumentException("Unterminated JSON array");
        char next = text.charAt(at++);
        if (next == ']') return list;
        if (next != ',') throw new IllegalArgumentException("Expected , or ]");
      }
    }

    String string() {
      skip();
      expect('"');
      StringBuilder out = new StringBuilder();
      while (true) {
        if (done()) throw new IllegalArgumentException("Unterminated string");
        char character = text.charAt(at++);
        if (character == '"') return out.toString();
        if (character != '\\') { out.append(character); continue; }
        if (done()) throw new IllegalArgumentException("Bad escape");
        char escaped = text.charAt(at++);
        switch (escaped) {
          case '"': out.append('"'); break;
          case '\\': out.append('\\'); break;
          case '/': out.append('/'); break;
          case 'b': out.append('\b'); break;
          case 'f': out.append('\f'); break;
          case 'n': out.append('\n'); break;
          case 'r': out.append('\r'); break;
          case 't': out.append('\t'); break;
          case 'u':
            if (at + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
            at += 4;
            break;
          default: throw new IllegalArgumentException("Bad escape \\" + escaped);
        }
      }
    }

    Object number() {
      int start = at;
      if (!done() && (text.charAt(at) == '-' || text.charAt(at) == '+')) at++;
      while (!done() && "0123456789.eE+-".indexOf(text.charAt(at)) >= 0) at++;
      String token = text.substring(start, at);
      if (token.isEmpty()) throw new IllegalArgumentException("Bad JSON value");
      try {
        if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
          return Long.parseLong(token);
        }
        return Double.parseDouble(token);
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("Bad number " + token);
      }
    }

    void expect(char character) {
      if (done() || text.charAt(at) != character) throw new IllegalArgumentException("Expected " + character);
      at++;
    }
  }
}

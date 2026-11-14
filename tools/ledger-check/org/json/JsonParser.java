package org.json;
public class JsonParser {
    private final String s; private int i;
    private JsonParser(String s) { this.s = s; this.i = 0; }
    public static Object parse(String text) { return new JsonParser(text).value(); }
    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private Object value() {
        ws();
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        String num = s.substring(start, i);
        if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
        return Long.parseLong(num);
    }
    private JSONObject obj() {
        JSONObject o = new JSONObject();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return o; }
        while (true) {
            ws();
            String k = str();
            ws();
            i++; // ':'
            o.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return o;
        }
    }
    private JSONArray arr() {
        JSONArray a = new JSONArray();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.put(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return a;
        }
    }
    private String str() {
        StringBuilder sb = new StringBuilder();
        i++; // opening quote
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            } else sb.append(c);
        }
    }
}

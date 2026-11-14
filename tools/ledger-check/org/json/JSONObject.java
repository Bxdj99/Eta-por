package org.json;
import java.util.*;
public class JSONObject {
    private final LinkedHashMap<String,Object> map = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String source) throws RuntimeException {
        Object parsed = JsonParser.parse(source);
        if (!(parsed instanceof JSONObject)) throw new RuntimeException("not an object");
        map.putAll(((JSONObject) parsed).map);
    }
    public JSONObject put(String k, Object v) { map.put(k, v); return this; }
    public JSONObject put(String k, boolean v) { map.put(k, v); return this; }
    public JSONObject put(String k, int v) { map.put(k, v); return this; }
    public JSONObject put(String k, long v) { map.put(k, v); return this; }
    public JSONObject putOpt(String k, Object v) { return put(k, v); }
    public Object opt(String k) { return map.get(k); }
    public String optString(String k) { Object o = map.get(k); return o == null ? "" : String.valueOf(o); }
    public String optString(String k, String d) { Object o = map.get(k); return o == null ? d : String.valueOf(o); }
    public int optInt(String k) { Object o = map.get(k); return o instanceof Number ? ((Number)o).intValue() : 0; }
    public int optInt(String k, int d) { Object o = map.get(k); return o instanceof Number ? ((Number)o).intValue() : d; }
    public long optLong(String k) { Object o = map.get(k); return o instanceof Number ? ((Number)o).longValue() : 0L; }
    public boolean optBoolean(String k) { Object o = map.get(k); return o instanceof Boolean && (Boolean)o; }
    public String getString(String k) { return optString(k); }
    public int getInt(String k) { return optInt(k); }
    public JSONArray getJSONArray(String k) { Object o = map.get(k); return o instanceof JSONArray ? (JSONArray)o : null; }
    public JSONArray optJSONArray(String k) { return getJSONArray(k); }
    public JSONObject getJSONObject(String k) { Object o = map.get(k); return o instanceof JSONObject ? (JSONObject)o : null; }
    public JSONObject optJSONObject(String k) { return getJSONObject(k); }
    public boolean has(String k) { return map.containsKey(k); }
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String,Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(quote(e.getKey())).append(":").append(valueToString(e.getValue()));
        }
        return sb.append("}").toString();
    }
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) { case '"': sb.append("\\\""); break; case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break; case '\r': sb.append("\\r"); break; case '\t': sb.append("\\t"); break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int)c)); else sb.append(c); }
        }
        return sb.append("\"").toString();
    }
    static String valueToString(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return quote((String)v);
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        return v.toString();
    }
    public static JSONObject parse(String s) { return (JSONObject) JsonParser.parse(s); }
}

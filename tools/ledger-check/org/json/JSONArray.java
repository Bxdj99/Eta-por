package org.json;
import java.util.*;
public class JSONArray {
    private final ArrayList<Object> list = new ArrayList<>();
    public JSONArray() {}
    public JSONArray put(Object v) { list.add(v); return this; }
    public int length() { return list.size(); }
    public Object get(int i) { return list.get(i); }
    public JSONObject getJSONObject(int i) { Object o = list.get(i); return o instanceof JSONObject ? (JSONObject)o : null; }
    public JSONObject optJSONObject(int i) { return i < 0 || i >= list.size() ? null : getJSONObject(i); }
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(","); sb.append(JSONObject.valueToString(list.get(i))); }
        return sb.append("]").toString();
    }
}

package dev.cloudsim.gateway.shell;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The relays the gateway app hands the bridge as "ice": {"relays":[{urls, username, credential}, …]}, each with its
 * own credentials (docs/architecture/RELAY_SPLIT.md 18), or one {urls, username, credential} from an app from before
 * the roster. An entry that cannot be read is left out; the others still serve.
 */
final class IceList {
    static final class Entry {
        final List<String> urls = new ArrayList<>();
        String username = "";
        String credential = "";
    }

    static List<Entry> parse(JSONObject ice) {
        List<Entry> out = new ArrayList<>();
        if (ice == null) return out;
        JSONArray relays = ice.optJSONArray("relays");
        if (relays == null) {
            add(out, ice);
            return out;
        }
        for (int i = 0; i < relays.length(); i++) add(out, relays.optJSONObject(i));
        return out;
    }

    private static void add(List<Entry> out, JSONObject relay) {
        JSONArray urls = relay == null ? null : relay.optJSONArray("urls");
        if (urls == null) return;
        Entry e = new Entry();
        for (int i = 0; i < urls.length(); i++) {
            String url = urls.optString(i);
            if (url.startsWith("stun:") || url.startsWith("turn:") || url.startsWith("turns:")) e.urls.add(url);
        }
        if (e.urls.isEmpty()) return;
        e.username = relay.optString("username");
        e.credential = relay.optString("credential");
        out.add(e);
    }

    private IceList() {}
}

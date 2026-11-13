package dev.cloudsim.gateway.shell;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class IceListTest {
    private static JSONObject relay(String user, String... urls) throws Exception {
        return new JSONObject().put("id", "r").put("urls", new JSONArray(Arrays.asList(urls))).put("username", user).put("credential", "p-" + user);
    }

    /** What an app from before the roster sends: one relay, as GET /v1/ice answers. */
    @Test public void oldShapeIsOneRelay() throws Exception {
        List<IceList.Entry> got = IceList.parse(relay("u", "turn:192.0.2.1:3478", "turn:192.0.2.1:3478?transport=tcp"));
        assertEquals(1, got.size());
        assertEquals(2, got.get(0).urls.size());
        assertEquals("u", got.get(0).username);
        assertEquals("p-u", got.get(0).credential);
    }

    /** The roster's candidates: each relay keeps its own credentials. */
    @Test public void eachRelayItsOwnCredentials() throws Exception {
        JSONObject ice = new JSONObject().put("relays", new JSONArray().put(relay("a", "turn:192.0.2.1:3478")).put(relay("b", "turns:relay.example:5349")));
        List<IceList.Entry> got = IceList.parse(ice);
        assertEquals(2, got.size());
        assertEquals("p-a", got.get(0).credential);
        assertEquals("turns:relay.example:5349", got.get(1).urls.get(0));
        assertEquals("p-b", got.get(1).credential);
    }

    /** Nothing, an empty answer, or an entry that is not a relay: no servers, and no exception to end the call on. */
    @Test public void unreadableEntriesAreLeftOut() throws Exception {
        assertEquals(0, IceList.parse(null).size());
        assertEquals(0, IceList.parse(new JSONObject()).size());
        JSONObject ice = new JSONObject().put("relays", new JSONArray().put("text").put(new JSONObject().put("urls", new JSONArray().put("https://example.com")))
            .put(relay("a", "turn:192.0.2.1:3478", "file:///etc/passwd")));
        List<IceList.Entry> got = IceList.parse(ice);
        assertEquals(1, got.size());
        assertEquals(1, got.get(0).urls.size());
    }
}

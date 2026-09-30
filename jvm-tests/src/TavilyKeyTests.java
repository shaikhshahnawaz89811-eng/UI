package tests;

import com.neonhud.app.core.search.TavilyClient;
import com.neonhud.app.core.search.TavilyKeyManager;
import com.neonhud.app.core.search.TavilyKeyStore;
import com.neonhud.app.core.search.TavilySnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Settings > Tavily API: Add tests the key first, only a good key is saved; Delete removes it for good. */
final class TavilyKeyTests {

    private static final String K1 = "tvly-dev-AAAABBBBCCCCDDDD1111";
    private static final String K2 = "tvly-dev-EEEEFFFFGGGGHHHH2222";

    static final class MemStore implements TavilyKeyStore {
        List<String> saved = new ArrayList<String>();
        public List<String> load() { return new ArrayList<String>(saved); }
        public void save(List<String> keys) { saved = new ArrayList<String>(keys); }
    }

    static final class FakeClient implements TavilyClient {
        volatile Outcome next = new Outcome(Kind.VALID, "");
        volatile CountDownLatch gate;          // when set, check() waits here (keeps a test "in flight")
        volatile int calls;
        public Outcome check(String apiKey) {
            calls++;
            CountDownLatch g = gate;
            if (g != null) { try { g.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
            return next;
        }
    }

    static void run() throws Exception {
        formatRules();
        addTestsThenSaves();
        badKeysAreNotSaved();
        oneTestAtATime();
        deleteAndPersistence();
        keyIsNeverShown();
    }

    private static void waitIdle(TavilyKeyManager m) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (m.snapshot().adding && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    static void formatRules() throws Exception {
        T.section("tavily: key format is checked before any network call");
        FakeClient c = new FakeClient();
        TavilyKeyManager m = new TavilyKeyManager(c, new MemStore());
        T.check(!m.requestAdd("").accepted, "empty rejected");
        T.check(!m.requestAdd("sk-abcdefghijklmnop").accepted, "wrong prefix rejected");
        T.check(!m.requestAdd("tvly-x").accepted, "too short rejected");
        T.check(!m.requestAdd("tvly-dev-abc$%^&*()_+defgh").accepted, "bad characters rejected");
        T.eq(0, c.calls, "no test request was sent for any of them");
        T.eq(0, m.snapshot().entries.size(), "nothing saved");
        T.eq(TavilySnapshot.Tone.ERROR, m.snapshot().tone, "the screen gets an error message");
        T.eq(K1, TavilyKeyManager.normalize("  " + K1.substring(0, 10) + "\n" + K1.substring(10) + " "), "pasted spaces / line breaks are removed");
    }

    static void addTestsThenSaves() throws Exception {
        T.section("tavily: Add = test with Tavily, then save");
        FakeClient c = new FakeClient();
        MemStore store = new MemStore();
        TavilyKeyManager m = new TavilyKeyManager(c, store);
        TavilyKeyManager.Result r = m.requestAdd(" " + K1 + " ");
        T.check(r.accepted, "good-looking key accepted for testing");
        waitIdle(m);
        T.eq(1, c.calls, "exactly one real test request");
        T.eq(1, m.snapshot().entries.size(), "key listed after a good test");
        T.eq(Arrays.asList(K1), store.saved, "key saved");
        T.eq(TavilySnapshot.Tone.OK, m.snapshot().tone, "success message");
        T.check(!m.requestAdd(K1).accepted, "same key cannot be added twice");
        T.eq(1, c.calls, "duplicate is refused without a test");

        c.next = new TavilyClient.Outcome(TavilyClient.Kind.LIMIT, "HTTP 432");
        m.requestAdd(K2);
        waitIdle(m);
        T.eq(2, m.snapshot().entries.size(), "valid key with used-up credits is still added");
        T.eq(TavilySnapshot.Tone.WARN, m.snapshot().tone, "...with a warning");
    }

    static void badKeysAreNotSaved() throws Exception {
        T.section("tavily: rejected / untestable keys are NOT saved");
        FakeClient c = new FakeClient();
        MemStore store = new MemStore();
        TavilyKeyManager m = new TavilyKeyManager(c, store);

        c.next = new TavilyClient.Outcome(TavilyClient.Kind.INVALID, "HTTP 401");
        m.requestAdd(K1); waitIdle(m);
        T.eq(0, m.snapshot().entries.size(), "wrong key (401) not added");
        T.eq(TavilySnapshot.Tone.ERROR, m.snapshot().tone, "wrong key -> error");

        c.next = new TavilyClient.Outcome(TavilyClient.Kind.NETWORK, "timeout");
        m.requestAdd(K1); waitIdle(m);
        T.eq(0, m.snapshot().entries.size(), "no internet -> not added");
        T.check(m.snapshot().message.toLowerCase().contains("internet"), "message says no internet");

        c.next = new TavilyClient.Outcome(TavilyClient.Kind.ERROR, "HTTP 500");
        m.requestAdd(K1); waitIdle(m);
        T.eq(0, m.snapshot().entries.size(), "server error -> not added");
        T.check(store.saved.isEmpty(), "nothing was written to storage");
        T.check(m.snapshot().canAdd(), "Add is usable again after a failed test");
    }

    static void oneTestAtATime() throws Exception {
        T.section("tavily: only one test at a time (real guard)");
        FakeClient c = new FakeClient();
        c.gate = new CountDownLatch(1);
        TavilyKeyManager m = new TavilyKeyManager(c, new MemStore());
        T.check(m.requestAdd(K1).accepted, "first test starts");
        T.check(m.snapshot().adding && !m.snapshot().canAdd(), "Add is locked while testing");
        T.check(!m.requestAdd(K2).accepted, "second Add refused while the first is running");
        c.gate.countDown();
        waitIdle(m);
        T.eq(1, m.snapshot().entries.size(), "only the first key was stored");
        T.eq(1, c.calls, "only one test request was made");
    }

    static void deleteAndPersistence() throws Exception {
        T.section("tavily: Delete and restart");
        FakeClient c = new FakeClient();
        MemStore store = new MemStore();
        TavilyKeyManager m = new TavilyKeyManager(c, store);
        m.requestAdd(K1); waitIdle(m);
        m.requestAdd(K2); waitIdle(m);
        TavilyKeyManager restarted = new TavilyKeyManager(c, store);
        T.eq(2, restarted.snapshot().entries.size(), "both keys survive an app restart");
        T.eq(K1, restarted.snapshot().entries.get(0).key, "order kept");

        T.check(m.requestDelete(K1).accepted, "delete works");
        T.eq(1, m.snapshot().entries.size(), "one key left");
        T.eq(Arrays.asList(K2), store.saved, "deleted key is gone from storage");
        T.check(!m.requestDelete(K1).accepted, "deleting it again does nothing");
        T.eq(1, new TavilyKeyManager(c, store).snapshot().entries.size(), "still deleted after restart");
        m.requestDelete(K2);
        T.eq(0, m.snapshot().entries.size(), "last key deleted");
        T.check(store.saved.isEmpty(), "storage empty");
        int before = c.calls;
        m.requestAdd(K1); waitIdle(m);
        T.eq(before + 1, c.calls, "a deleted key is tested again when re-added");
        T.eq(1, m.snapshot().entries.size(), "and can be added back");
    }

    static void keyIsNeverShown() throws Exception {
        T.section("tavily: the full key is never displayed");
        String masked = TavilyKeyManager.mask(K1);
        T.check(!masked.contains("AAAABBBB"), "middle of the key hidden");
        T.check(masked.endsWith("1111"), "last 4 shown to tell keys apart");
        T.check(masked.startsWith("tvly-dev-"), "prefix shown");
        T.check(TavilyKeyManager.mask("tvly-abcdefgh1234").startsWith("tvly-"), "plain key masked too");
        T.check(!TavilyKeyManager.mask("tvly-abcdefgh1234").contains("abcdefgh"), "plain key middle hidden");
    }
}

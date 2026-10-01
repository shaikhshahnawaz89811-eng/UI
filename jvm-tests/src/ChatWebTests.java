package tests;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.neonhud.app.core.chat.ChatController;
import com.neonhud.app.core.memory.ConversationBrain;
import com.neonhud.app.core.memory.InMemoryStore;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.web.HttpWebApi;
import com.neonhud.app.core.web.KeySource;
import com.neonhud.app.core.web.WebApi;
import com.neonhud.app.core.web.WebMode;
import com.neonhud.app.core.web.WebSearchService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Phase 1 part 2: web search connected to the chat (prompt, status text, links + pictures, notices, Settings mode, real HTTP). */
final class ChatWebTests {

    private static final class Rig {
        final Fakes.FakeEngine engine = new Fakes.FakeEngine();
        final InMemoryStore store = new InMemoryStore();
        final ChatController chat;
        final ConversationBrain brain;
        final FakeWebApi api;
        final WebSearchService service;
        volatile WebMode mode = WebMode.AUTO;
        volatile List<String> keys;

        Rig(FakeWebApi.Handler h, List<String> keys) throws Exception {
            this.keys = keys;
            Fakes.FakeStorage s = new Fakes.FakeStorage();
            ModuleManager mm = new ModuleManager(engine, s, new Fakes.MemStateStore(null));
            mm.requestImport(Fakes.src("g.litertlm", 5)); Fakes.awaitIdle(mm);
            mm.requestLoad(); Fakes.awaitIdle(mm);
            brain = new ConversationBrain(store, new ConversationBrain.Clock() { public long now() { return System.currentTimeMillis(); } });
            chat = new ChatController(mm, brain, store);
            api = new FakeWebApi(h);
            service = new WebSearchService(api, new KeySource() { public List<String> keys() { return Rig.this.keys; } },
                    new WebSearchService.Settings() { public WebMode mode() { return mode; } },
                    new WebSearchService.Clock() { public long now() { return System.currentTimeMillis(); } });
            chat.setWebSearch(service);
        }

        ChatController.Item lastAi() {
            List<ChatController.Item> items = chat.items();
            for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).kind == ChatController.Kind.AI) return items.get(i);
            return null;
        }
        ChatController.Item lastNotice() {
            List<ChatController.Item> items = chat.items();
            ChatController.Item last = items.isEmpty() ? null : items.get(items.size() - 1);
            return last != null && last.kind == ChatController.Kind.NOTICE ? last : null;
        }
        void send(String text) {
            ChatController.SendResult r = chat.send(text);
            T.eq(ChatController.SendResult.ACCEPTED, r, "send accepted: " + text);
            ChatTests.waitIdle(chat);
        }
    }

    private static List<String> key(String... k) { return new ArrayList<String>(Arrays.asList(k)); }

    static void run() throws Exception {
        T.section("phase 1 part 2: web search in the chat");

        // ---------------------------------------------------------------- a plain chat message never goes online
        Rig r = new Rig(FakeWebApi.ok(), key("tvly-aaaaaaaaaaaaaaaa"));
        r.send("hello kaise ho");
        T.eq(0, r.api.calls.size(), "plain chat: no search call");
        T.eq("", r.engine.lastPrompt.webContext, "plain chat: no web context in the prompt");
        T.check(r.lastAi() != null && r.lastAi().links.isEmpty() && r.lastAi().pics.isEmpty(), "plain chat: no links / pictures under the reply");
        T.check(r.lastNotice() == null, "plain chat: no notice");

        // ---------------------------------------------------------------- a fresh-fact question: searched, context reaches the model, links under the reply
        r.engine.cannedReply = "Ye rahi jaankari. Dono sources yahi kehte hain.";
        r.send("iPhone 16 ka price kitna hai aaj?");
        T.check(r.api.calls.size() == 1, "price question: exactly one search");
        T.check(r.engine.lastPrompt.webContext.contains("WEB SEARCH RESULTS"), "price question: results reach the model prompt");
        T.check(r.engine.lastPrompt.flatten().contains("WEB SEARCH RESULTS"), "price question: flatten() carries the web block");
        String flat = r.engine.lastPrompt.flatten();
        T.check(flat.indexOf("WEB SEARCH RESULTS") > 0 && flat.indexOf("WEB SEARCH RESULTS") < flat.lastIndexOf("CURRENT USER MESSAGE:\n"),
                "price question: web block sits right before the user message");
        T.check(!r.engine.lastPrompt.userMessage.contains("WEB SEARCH"), "price question: user message itself is untouched");
        T.check(!r.engine.lastPrompt.systemInstruction().contains("WEB SEARCH RESULTS"), "price question: system slot stays clean");
        ChatController.Item ai = r.lastAi();
        T.check(ai != null && !ai.pending && ai.text.startsWith("Ye rahi jaankari"), "price question: reply finished with its text");
        T.check(ai != null && ai.status.isEmpty(), "price question: no leftover status line");
        T.check(r.lastAi().pics.isEmpty(), "price question: no pictures unless asked");
        boolean stored = false;
        for (com.neonhud.app.core.memory.ConversationMessage m : r.store.lastMessages(50)) if (m.content.contains("WEB SEARCH")) stored = true;
        T.check(!stored, "price question: web context is never saved into the conversation history");

        // ---------------------------------------------------------------- answers built from the net are not turned into long-term memories
        Rig m = new Rig(FakeWebApi.ok(), key("tvly-mmmmmmmmmmmmmmmm"));
        m.engine.cannedReply = "Mera naam Gemma hai. Aapka favourite color blue hai aur aap Pune mein rehte ho.";
        int before = memCount(m);
        m.send("Bitcoin ka rate abhi kya hai?");
        int afterWeb = memCount(m);
        Rig m2 = new Rig(FakeWebApi.ok(), key("tvly-mmmmmmmmmmmmmmmm"));
        m2.engine.cannedReply = m.engine.cannedReply;
        m2.mode = WebMode.OFF;
        m2.send("Bitcoin ka rate abhi kya hai?");
        int afterOffline = memCount(m2);
        T.check(afterOffline > 0, "control: the same reply WITHOUT web does create memories (" + afterOffline + ")");
        T.eq(0, afterWeb - before, "web-built answer: no long-term memory added");

        // ---------------------------------------------------------------- pictures: shown under the reply
        Rig p = new Rig(FakeWebApi.ok(), key("tvly-pppppppppppppppp"));
        p.send("Taj Mahal ki photo dikhao");
        T.check(p.api.calls.size() == 1 && p.api.requests.get(0).includeImages, "photo request: search asks for images");
        T.check(!p.lastAi().pics.isEmpty() && p.lastAi().pics.size() <= 4, "photo request: pictures shown under the reply");
        T.check(p.engine.lastPrompt.webContext.contains("picture"), "photo request: the model is told pictures are shown");

        // ---------------------------------------------------------------- "uski photo dikhao" finds its subject from the last search
        p.send("uski photo dikhao");
        T.check(p.api.requests.size() >= 2 && p.api.requests.get(p.api.requests.size() - 1).query.toLowerCase().contains("taj"),
                "follow-up: 'uski photo dikhao' searches for the previous subject (" + p.api.requests.get(p.api.requests.size() - 1).query + ")");

        // ---------------------------------------------------------------- read-only: searched, but nothing shown
        Rig ro = new Rig(FakeWebApi.ok(), key("tvly-rrrrrrrrrrrrrrrr"));
        ro.send("Taj Mahal ke baare mein net se padh ke batao, photo dikhana mat");
        T.check(ro.api.calls.size() >= 1, "read-only: it still searches");
        T.check(ro.lastAi().pics.isEmpty() && ro.lastAi().links.isEmpty(), "read-only: no pictures, no links under the reply");

        // ---------------------------------------------------------------- image read without showing: "photo mat dikhana" is a display constraint, not a vision constraint
        Rig rir = new Rig(FakeWebApi.ok(), key("tvly-ririririririririr"));
        rir.send("Taj Mahal ki photo padh ke batao, photo mat dikhana");
        T.check(rir.api.calls.size() == 1 && rir.api.requests.get(0).includeImages, "image-read + no-show: search still requests image sources");
        T.check(rir.lastAi().pics.isEmpty(), "image-read + no-show: pictures are not rendered under the reply");
        T.check(rir.engine.lastPrompt.webContext.contains("picture"), "image-read + no-show: picture context reaches the model");

        // ---------------------------------------------------------------- explicit online search wording variants
        Rig ex = new Rig(FakeWebApi.ok(), key("tvly-exexexexexexexex"));
        ex.send("online Mumbai metro update dhoondho");
        T.check(ex.api.calls.size() == 1, "online+dhoondho: explicit search reaches Tavily");

        // ---------------------------------------------------------------- link request
        Rig lk = new Rig(FakeWebApi.ok(), key("tvly-llllllllllllllll"));
        lk.send("Python ki official website ka link do");
        T.check(!lk.lastAi().links.isEmpty(), "link request: a link sits under the reply");
        T.check(lk.lastAi().links.get(0).url.startsWith("http"), "link request: the link is a real address from the results");

        // ---------------------------------------------------------------- status text shows while searching, cleared once text flows
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        final AtomicReference<Rig> self = new AtomicReference<Rig>();
        Rig st = new Rig(new FakeWebApi.Handler() {
            public WebApi.SearchResponse handle(String k, WebApi.SearchRequest req) throws WebApi.WebApiException {
                ChatController.Item cur = self.get().lastAi();
                seen.add(cur == null ? "none" : cur.status + "|" + cur.text + "|" + cur.pending);
                return FakeWebApi.ok().handle(k, req);
            }
        }, key("tvly-ssssssssssssssss"));
        self.set(st);
        st.send("aaj ka mausam Delhi ka kaisa hai");
        T.check(seen.size() == 1 && seen.get(0).equals(ChatController.STATUS_SEARCHING + "||true"),
                "status: the user sees 'Searching the web' while the search runs (" + seen + ")");
        T.check(st.lastAi().status.isEmpty() && !st.lastAi().pending, "status: gone when the reply is done");

        // ---------------------------------------------------------------- Settings mode is read on every message
        Rig sm = new Rig(FakeWebApi.ok(), key("tvly-tttttttttttttttt"));
        sm.mode = WebMode.OFF;
        sm.send("iPhone 16 ka price kitna hai aaj?");
        T.eq(0, sm.api.calls.size(), "mode OFF: no search at all");
        T.eq("", sm.engine.lastPrompt.webContext, "mode OFF: nothing added to the prompt");
        sm.mode = WebMode.AUTO;
        sm.send("iPhone 16 ka price kitna hai aaj?");
        T.check(sm.api.calls.size() == 1, "switched back to Auto: the very next message searches");
        sm.mode = WebMode.ALWAYS;
        sm.send("Bharat ki rajdhani kaun si hai?");
        T.check(sm.api.calls.size() == 2, "mode ALWAYS: an ordinary question is searched too");
        sm.mode = WebMode.OFF;
        sm.send("net pe search karo iPhone 16 price");
        T.check(sm.lastNotice() != null && sm.lastNotice().text.contains("band"), "mode OFF + explicit ask: the user is told it is off");

        // ---------------------------------------------------------------- failures: clear notice after the reply, model told not to pretend
        Rig nk = new Rig(FakeWebApi.ok(), new ArrayList<String>());
        nk.send("iPhone 16 ka price kitna hai aaj?");
        T.eq(0, nk.api.calls.size(), "no key: nothing is sent");
        T.check(nk.engine.lastPrompt.webContext.contains("COULD NOT BE USED"), "no key: the model is told the search did not happen");
        T.check(nk.lastNotice() == null, "no key + implicit search: no nagging notice (the model says it in one sentence instead)");
        T.check(nk.lastAi() != null && !nk.lastAi().text.isEmpty(), "no key: the reply itself still arrives");
        nk.send("net pe search karo iPhone 16 ka price");
        T.check(nk.lastNotice() != null && nk.lastNotice().text.contains("Tavily"), "no key + explicit ask: the user is told to add a key in Settings");

        Rig lim = new Rig(new FakeWebApi.Handler() {
            public WebApi.SearchResponse handle(String k, WebApi.SearchRequest q) throws WebApi.WebApiException { throw FakeWebApi.fail(WebApi.Fail.LIMIT); }
        }, key("tvly-l1l1l1l1l1l1l1l1", "tvly-l2l2l2l2l2l2l2l2"));
        lim.send("iPhone 16 ka price kitna hai aaj?");
        T.eq(2, lim.api.calls.size(), "all keys limited: both keys were tried");
        T.check(lim.lastNotice() != null && lim.lastNotice().text.contains("limit"), "all keys limited: clear notice");
        T.check(lim.lastAi().links.isEmpty(), "all keys limited: no links");

        Rig net = new Rig(new FakeWebApi.Handler() {
            public WebApi.SearchResponse handle(String k, WebApi.SearchRequest q) throws WebApi.WebApiException { throw FakeWebApi.fail(WebApi.Fail.NETWORK); }
        }, key("tvly-nnnnnnnnnnnnnnnn", "tvly-n2n2n2n2n2n2n2n2"));
        net.send("Taj Mahal ki photo dikhao");
        T.check(net.lastNotice() != null && net.lastNotice().text.contains("Internet"), "no internet: clear notice");
        T.check(net.lastAi().pics.isEmpty(), "no internet: no pictures");
        T.check(net.engine.lastPrompt.webContext.contains("cannot show pictures"), "no internet: the model is told it cannot show pictures");

        // a thrown RuntimeException inside the web layer must not break the reply
        Rig bad = new Rig(new FakeWebApi.Handler() {
            public WebApi.SearchResponse handle(String k, WebApi.SearchRequest q) { throw new IllegalStateException("boom"); }
        }, key("tvly-bbbbbbbbbbbbbbbb"));
        bad.send("iPhone 16 ka price kitna hai aaj?");
        T.check(bad.lastAi() != null && !bad.lastAi().text.isEmpty(), "web layer crash: the reply still arrives");

        // engine failure after a good search: no links, no stale status
        Rig ef = new Rig(FakeWebApi.ok(), key("tvly-eeeeeeeeeeeeeeee"));
        ef.engine.cannedReply = "";
        ef.send("iPhone 16 ka price kitna hai aaj?");
        T.check(ef.lastAi() == null, "empty model reply: the empty bubble is removed");
        T.check(ef.lastNotice() != null, "empty model reply: the user is told");

        // attachments: planner is told a file is attached (no picture search for 'is photo mein kya hai')
        Rig at = new Rig(FakeWebApi.ok(), key("tvly-aaaaaaaaaaaaaaaa"));
        ChatController.SendResult sr = at.chat.send("is photo mein kya hai", Collections.singletonList(
                new com.neonhud.app.core.engine.Attachment(com.neonhud.app.core.engine.Attachment.Kind.IMAGE, "x.png", 10, "uri")));
        ChatTests.waitIdle(at.chat);
        T.eq(ChatController.SendResult.ACCEPTED, sr, "attachment message accepted");
        T.eq(0, at.api.calls.size(), "attached picture question: no web picture search");

        // the coding chat has no search service: it never goes online
        Rig cd = new Rig(FakeWebApi.ok(), key("tvly-cccccccccccccccc"));
        cd.chat.setWebSearch(null);
        cd.send("iPhone 16 ka price kitna hai aaj?");
        T.eq(0, cd.api.calls.size(), "chat without a search service: fully offline");
        T.eq("", cd.engine.lastPrompt.webContext, "chat without a search service: prompt unchanged");

        httpTests();
    }

    private static int memCount(Rig r) {
        int n = r.store.countMemories(0);
        for (long id : r.brain.topicIds()) n += r.store.countMemories(id);
        return n;
    }

    // ==================================================================== real HTTP against a local server

    private static void httpTests() throws Exception {
        T.section("HttpWebApi: real HTTP against a local server");
        final AtomicReference<String> gotAuth = new AtomicReference<String>();
        final AtomicReference<String> gotBody = new AtomicReference<String>();
        final AtomicReference<String> gotPath = new AtomicReference<String>();
        final AtomicInteger status = new AtomicInteger(200);
        final AtomicReference<String> answer = new AtomicReference<String>(
                "{\"answer\":\"Delhi is hot.\",\"results\":[{\"title\":\"Weather\",\"url\":\"https://w.example.com/d\",\"content\":\"35C and sunny\",\"score\":0.9}],"
              + "\"images\":[{\"url\":\"https://img.example.com/a.jpg\",\"description\":\"sky\"}]}");
        final AtomicInteger delayMs = new AtomicInteger(0);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) throws java.io.IOException {
                gotAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
                gotPath.set(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
                InputStream in = ex.getRequestBody();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                gotBody.set(bos.toString("UTF-8"));
                if (delayMs.get() > 0) { try { Thread.sleep(delayMs.get()); } catch (InterruptedException ignored) { } }
                byte[] out = answer.get().getBytes("UTF-8");
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status.get(), out.length);
                OutputStream os = ex.getResponseBody();
                try { os.write(out); } finally { os.close(); }
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
            HttpWebApi api = new HttpWebApi(url, 2000, 1500);
            WebApi.SearchRequest req = new WebApi.SearchRequest("Delhi weather \"today\"", 5, false, true, "general", "");

            WebApi.SearchResponse ok = api.search("tvly-secret-key-1234", req);
            T.eq("POST /search", gotPath.get(), "http: POST to the search path");
            T.eq("Bearer tvly-secret-key-1234", gotAuth.get(), "http: key sent as a Bearer header");
            T.check(gotBody.get().contains("\"query\":\"Delhi weather \\\"today\\\"\""), "http: query is JSON-escaped in the body (" + gotBody.get() + ")");
            T.check(gotBody.get().contains("\"include_images\":true"), "http: image flag sent");
            T.eq("Delhi is hot.", ok.answer, "http: answer parsed");
            T.eq(1, ok.hits.size(), "http: one hit parsed");
            T.eq("https://w.example.com/d", ok.hits.get(0).url, "http: hit url parsed");
            T.eq(1, ok.images.size(), "http: image parsed");

            int[][] map = {{401, 0}, {403, 0}, {429, 1}, {432, 1}, {433, 1}, {400, 2}, {422, 2}, {408, 3}, {504, 3}, {500, 4}, {503, 4}};
            WebApi.Fail[] kinds = {WebApi.Fail.INVALID_KEY, WebApi.Fail.LIMIT, WebApi.Fail.BAD_REQUEST, WebApi.Fail.TIMEOUT, WebApi.Fail.SERVER};
            for (int[] row : map) {
                status.set(row[0]);
                String old = answer.get();
                answer.set("{\"detail\":{\"error\":\"nope\"}}");
                try {
                    api.search("tvly-x", req);
                    T.check(false, "http " + row[0] + ": should fail");
                } catch (WebApi.WebApiException e) {
                    T.eq(kinds[row[1]], e.fail, "http " + row[0] + " -> " + kinds[row[1]]);
                    T.check(!String.valueOf(e.getMessage()).contains("tvly-x"), "http " + row[0] + ": the key never appears in the error");
                }
                answer.set(old);
            }
            status.set(200);

            answer.set("<html>this is not json</html>");
            try { api.search("tvly-x", req); T.check(false, "garbage body should fail"); }
            catch (WebApi.WebApiException e) { T.eq(WebApi.Fail.PARSE, e.fail, "garbage 200 answer -> PARSE"); }
            answer.set("{\"results\":[],\"images\":[]}");
            T.check(api.search("tvly-x", req).hits.isEmpty(), "empty answer -> zero hits, no exception");

            answer.set("{\"answer\":\"" + repeat('x', HttpWebApi_MAX() + 10) + "\",\"results\":[]}");
            try { api.search("tvly-x", req); T.check(false, "huge body should fail"); }
            catch (WebApi.WebApiException e) { T.eq(WebApi.Fail.PARSE, e.fail, "oversized answer is refused"); }
            answer.set("{\"results\":[]}");

            delayMs.set(2600);
            long t0 = System.currentTimeMillis();
            try { api.search("tvly-x", req); T.check(false, "slow server should time out"); }
            catch (WebApi.WebApiException e) { T.eq(WebApi.Fail.TIMEOUT, e.fail, "slow server -> TIMEOUT"); }
            T.check(System.currentTimeMillis() - t0 < 2400, "timeout fires near the read limit (" + (System.currentTimeMillis() - t0) + " ms)");
            delayMs.set(0);
        } finally {
            server.stop(0);
        }

        // nothing listening on that port any more
        try {
            new HttpWebApi("http://127.0.0.1:" + server.getAddress().getPort() + "/search", 1000, 1000)
                    .search("tvly-x", new WebApi.SearchRequest("q", 3, false, false, "general", ""));
            T.check(false, "closed port should fail");
        } catch (WebApi.WebApiException e) {
            T.eq(WebApi.Fail.NETWORK, e.fail, "connection refused -> NETWORK");
        }

        // the real service on top of the real HTTP client: key failover over real status codes
        final AtomicInteger hits = new AtomicInteger();
        HttpServer s2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s2.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) throws java.io.IOException {
                hits.incrementAndGet();
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                boolean good = auth != null && auth.endsWith("goodgoodgoodgood");
                byte[] out = (good
                        ? "{\"answer\":\"ok\",\"results\":[{\"title\":\"T\",\"url\":\"https://a.example.com/x\",\"content\":\"facts about it\",\"score\":0.8}]}"
                        : "{\"detail\":\"limit\"}").getBytes("UTF-8");
                ex.sendResponseHeaders(good ? 200 : 432, out.length);
                OutputStream os = ex.getResponseBody();
                try { os.write(out); } finally { os.close(); }
            }
        });
        s2.start();
        try {
            String url2 = "http://127.0.0.1:" + s2.getAddress().getPort() + "/search";
            final List<String> ks = key("tvly-limitedlimitedlim", "tvly-goodgoodgoodgood");
            WebSearchService svc = new WebSearchService(new HttpWebApi(url2, 2000, 2000),
                    new KeySource() { public List<String> keys() { return ks; } },
                    new WebSearchService.Settings() { public WebMode mode() { return WebMode.AUTO; } },
                    new WebSearchService.Clock() { public long now() { return System.currentTimeMillis(); } });
            com.neonhud.app.core.web.WebTurn t = svc.prepare("iPhone 16 ka price kitna hai aaj?", com.neonhud.app.core.web.PlanContext.auto());
            T.check(t.ok(), "service + real HTTP: first key limited (432), second key works");
            T.eq(2, hits.get(), "service + real HTTP: exactly two requests reached the server");
            T.check(t.context.contains("facts about it"), "service + real HTTP: the answer reached the model context");
        } finally {
            s2.stop(0);
        }
    }

    private static int HttpWebApi_MAX() { return 2 * 1024 * 1024; }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }
}

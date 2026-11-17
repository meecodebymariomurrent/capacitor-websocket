package at.meecode.capacitorwebsocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SocketSessionTest {

    /** Controllable transport: records what the session asks for, the test plays the server. */
    static final class FakeTransport implements SocketSession.Transport {
        final SocketSession.TransportListener listener;
        final List<String> sent = new ArrayList<>();
        final List<Integer> closeCodes = new ArrayList<>();
        boolean cancelled = false;
        boolean acceptSends = true;

        FakeTransport(SocketSession.TransportListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean send(String text) {
            if (acceptSends) {
                sent.add(text);
            }
            return acceptSends;
        }

        @Override
        public void close(int code) {
            closeCodes.add(code);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    static final class Recorder implements SocketSession.ConnectCallback {
        int resolved = 0;
        final List<String> rejections = new ArrayList<>();

        @Override
        public void resolve() {
            resolved++;
        }

        @Override
        public void reject(String message, Exception cause) {
            rejections.add(message);
        }
    }

    static final class Event {
        final String name;
        final Map<String, Object> payload;

        Event(String name, Map<String, Object> payload) {
            this.name = name;
            this.payload = payload;
        }
    }

    private final List<FakeTransport> transports = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private SocketSession session;

    @Before
    public void setUp() {
        transports.clear();
        events.clear();
        session = new SocketSession(
                "s",
                listener -> {
                    FakeTransport t = new FakeTransport(listener);
                    transports.add(t);
                    return t;
                },
                (event, payload) -> events.add(new Event(event, payload)));
    }

    // ---- helpers ----

    private FakeTransport last() {
        return transports.get(transports.size() - 1);
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (Event e : events) {
            names.add(e.name);
        }
        return names;
    }

    private List<Object> states() {
        List<Object> states = new ArrayList<>();
        for (Event e : events) {
            if (e.name.equals("s:statechange")) {
                states.add(e.payload.get("state"));
            }
        }
        return states;
    }

    private Event first(String name) {
        for (Event e : events) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    private List<Event> all(String name) {
        List<Event> found = new ArrayList<>();
        for (Event e : events) {
            if (e.name.equals(name)) {
                found.add(e);
            }
        }
        return found;
    }

    private Recorder open() {
        Recorder r = new Recorder();
        session.connect(r);
        last().listener.onOpen(Collections.<String, List<String>>emptyMap());
        return r;
    }

    // ---- connect ----

    @Test
    public void connectResolvesOnlyOnceTheSocketIsOpen() {
        Recorder r = new Recorder();
        session.connect(r);
        assertEquals(0, r.resolved);
        assertFalse(session.isConnected());

        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("server", Arrays.asList("x"));
        last().listener.onOpen(headers);

        assertEquals(1, r.resolved);
        assertTrue(session.isConnected());
        assertEquals(headers, first("s:connected").payload.get("headers"));
    }

    @Test
    public void statesFollowConnectingOpenClosingClosed() {
        open();
        session.disconnect();
        last().listener.onClosed(1000, "");
        assertEquals(Arrays.<Object>asList("CONNECTING", "OPEN", "CLOSING", "CLOSED"), states());
    }

    @Test
    public void failedConnectEmitsConnectErrorAndRejects() {
        Recorder r = new Recorder();
        session.connect(r);
        last().listener.onFailure(new java.io.IOException("refused"));

        assertEquals(1, r.rejections.size());
        assertTrue(r.rejections.get(0).contains("refused"));
        assertNotNull(first("s:connecterror"));
        assertNull(first("s:error"));
        assertFalse(session.isConnected());
        Event disconnected = first("s:disconnected");
        assertEquals(false, disconnected.payload.get("closedByServer"));
    }

    @Test
    public void connectWhileConnectedResolvesWithoutANewTransport() {
        open();
        Recorder again = new Recorder();
        session.connect(again);
        assertEquals(1, again.resolved);
        assertEquals(1, transports.size());
    }

    @Test
    public void secondConnectWhileConnectingIsRejected() {
        session.connect(new Recorder());
        Recorder second = new Recorder();
        session.connect(second);
        assertEquals(1, second.rejections.size());
        assertTrue(second.rejections.get(0).contains("already connecting"));
        assertEquals(1, transports.size());
    }

    @Test
    public void reconnectUsesAFreshTransport() {
        open();
        last().listener.onClosed(1001, "bye");
        Recorder second = new Recorder();
        session.connect(second);
        assertEquals(2, transports.size());
        last().listener.onOpen(Collections.<String, List<String>>emptyMap());
        assertEquals(1, second.resolved);
        assertTrue(session.isConnected());
    }

    @Test
    public void reconnectAfterAFailureWorks() {
        session.connect(new Recorder());
        last().listener.onFailure(new RuntimeException("boom"));
        Recorder second = new Recorder();
        session.connect(second);
        last().listener.onOpen(Collections.<String, List<String>>emptyMap());
        assertEquals(1, second.resolved);
    }

    // ---- messages ----

    @Test
    public void textAndBinaryMessages() {
        open();
        last().listener.onText("hi");
        last().listener.onBinary("AQID");
        List<Event> messages = all("s:message");
        assertEquals("hi", messages.get(0).payload.get("data"));
        assertFalse(messages.get(0).payload.containsKey("binary"));
        assertEquals("AQID", messages.get(1).payload.get("data"));
        assertEquals(true, messages.get(1).payload.get("binary"));
    }

    @Test
    public void messagesKeepTheirOrder() {
        open();
        last().listener.onText("1");
        last().listener.onText("");
        last().listener.onText("3");
        List<Object> data = new ArrayList<>();
        for (Event e : all("s:message")) {
            data.add(e.payload.get("data"));
        }
        assertEquals(Arrays.<Object>asList("1", "", "3"), data);
    }

    // ---- closing ----

    @Test
    public void serverCloseEmitsCloseFrameThenDisconnected() {
        open();
        last().listener.onClosing(1001, "bye");
        last().listener.onClosed(1001, "bye");

        List<String> relevant = new ArrayList<>();
        for (String n : names()) {
            if (n.equals("s:closeframe") || n.equals("s:disconnected")) {
                relevant.add(n);
            }
        }
        assertEquals(Arrays.asList("s:closeframe", "s:disconnected"), relevant);
        assertEquals("1001 bye", first("s:closeframe").payload.get("frame"));

        Map<String, Object> d = first("s:disconnected").payload;
        assertEquals(true, d.get("closedByServer"));
        assertEquals("1001 bye", d.get("serverCloseFrame"));
        assertEquals("", d.get("clientCloseFrame"));
        assertFalse(session.isConnected());
    }

    @Test
    public void clientDisconnectClosesNormallyAndReportsTheClientFrame() {
        open();
        session.disconnect();
        assertEquals(Arrays.asList(1000), last().closeCodes);
        assertFalse(session.isConnected());

        last().listener.onClosed(1000, "");
        Map<String, Object> d = first("s:disconnected").payload;
        assertEquals(false, d.get("closedByServer"));
        assertEquals("", d.get("serverCloseFrame"));
        assertEquals("1000", d.get("clientCloseFrame"));
        assertNull(first("s:closeframe"));
    }

    @Test
    public void failureAfterConnectingEmitsErrorAndDisconnected() {
        open();
        last().listener.onFailure(new java.io.IOException("reset"));
        assertNotNull(first("s:error"));
        assertNull(first("s:connecterror"));
        assertEquals(true, first("s:disconnected").payload.get("closedByServer"));
        assertFalse(session.isConnected());
    }

    @Test
    public void disconnectOnAnIdleSessionIsANoOp() {
        session.disconnect();
        assertTrue(events.isEmpty());
        assertTrue(transports.isEmpty());
    }

    @Test
    public void disconnectWhileConnectingRejectsThePendingConnectAndCancelsTheTransport() {
        Recorder r = new Recorder();
        session.connect(r);
        session.disconnect();

        assertEquals(Arrays.asList("Connection attempt aborted"), r.rejections);
        assertTrue(last().cancelled);
        assertFalse(session.isConnected());
        // and it can be connected again
        Recorder again = new Recorder();
        session.connect(again);
        assertEquals(2, transports.size());
        last().listener.onOpen(Collections.<String, List<String>>emptyMap());
        assertEquals(1, again.resolved);
    }

    // ---- stale events ----

    @Test
    public void disposeRejectsPendingConnectAndSilencesTheOldTransport() {
        Recorder r = new Recorder();
        session.connect(r);
        FakeTransport old = last();
        session.dispose();

        assertEquals(Arrays.asList("Connection attempt aborted"), r.rejections);
        assertTrue(old.cancelled);
        events.clear();
        old.listener.onOpen(Collections.<String, List<String>>emptyMap());
        old.listener.onText("late");
        old.listener.onClosed(1000, "late");
        assertTrue(events.isEmpty());
        assertFalse(session.isConnected());
    }

    @Test
    public void lateEventsFromAnOldTransportDoNotDisturbTheNewConnection() {
        open();
        FakeTransport old = last();
        session.disconnect(); // old transport is closing, its close callback has not arrived yet

        Recorder r = new Recorder();
        session.connect(r);
        last().listener.onOpen(Collections.<String, List<String>>emptyMap());
        events.clear();

        old.listener.onText("late");
        old.listener.onClosing(1000, "late");
        old.listener.onClosed(1000, "late");
        old.listener.onFailure(new RuntimeException("late"));

        assertTrue(events.isEmpty());
        assertTrue(session.isConnected());
        assertNull(session.send("still works"));
        assertEquals(Arrays.asList("still works"), last().sent);
    }

    @Test
    public void aTransportThatFailsBeforeOpenReturnedIsNotKept() {
        // Failure is delivered synchronously from inside factory.open(), like a transport that fails instantly.
        final List<FakeTransport> created = new ArrayList<>();
        SocketSession instant = new SocketSession(
                "s",
                listener -> {
                    FakeTransport t = new FakeTransport(listener);
                    created.add(t);
                    listener.onFailure(new java.io.IOException("instant"));
                    return t;
                },
                (event, payload) -> events.add(new Event(event, payload)));

        Recorder r = new Recorder();
        instant.connect(r);

        assertEquals(1, r.rejections.size());
        assertTrue(created.get(0).cancelled);
        assertFalse(instant.isConnected());
        assertEquals("Socket 's' is not connected", instant.send("x"));
    }

    // ---- send ----

    @Test
    public void sendRejectsWhenNotConnected() {
        assertEquals("Socket 's' is not connected", session.send("x"));
        session.connect(new Recorder());
        assertEquals("Socket 's' is not connected", session.send("x"));
    }

    @Test
    public void sendWritesToTheTransportOnceConnected() {
        open();
        assertNull(session.send("ping"));
        assertEquals(Arrays.asList("ping"), last().sent);
    }

    @Test
    public void sendRejectsAfterDisconnectAndAfterServerClose() {
        open();
        session.disconnect();
        assertEquals("Socket 's' is not connected", session.send("late"));
        assertTrue(last().sent.isEmpty());

        session.connect(new Recorder());
        last().listener.onOpen(Collections.<String, List<String>>emptyMap());
        last().listener.onClosed(1001, "bye");
        assertEquals("Socket 's' is not connected", session.send("late"));
    }

    @Test
    public void sendFailureEmitsSendErrorAndRejects() {
        open();
        last().acceptSends = false;
        assertEquals("Couldnt send message", session.send("payload"));
        Map<String, Object> e = first("s:senderror").payload;
        assertEquals("payload", e.get("frame"));
        assertNotNull(e.get("cause"));
    }

    // ---- contract ----

    @Test
    public void everyEmittedEventIsDocumentedWithTheDocumentedPayloadKeys() {
        Map<String, List<String>> contract = new LinkedHashMap<>();
        contract.put("connected", Arrays.asList("headers"));
        contract.put("message", Arrays.asList("data"));
        contract.put("disconnected", Arrays.asList("clientCloseFrame", "closedByServer", "serverCloseFrame"));
        contract.put("statechange", Arrays.asList("state"));
        contract.put("closeframe", Arrays.asList("frame"));
        contract.put("connecterror", Arrays.asList("exception"));
        contract.put("error", Arrays.asList("cause"));
        contract.put("senderror", Arrays.asList("cause", "frame"));

        // happy path, binary, server close, failed connect, client disconnect, failed send
        open();
        last().listener.onText("t");
        last().listener.onBinary("AQ==");
        last().acceptSends = false;
        session.send("x");
        last().acceptSends = true;
        last().listener.onClosing(1001, "bye");
        last().listener.onClosed(1001, "bye");
        session.connect(new Recorder());
        last().listener.onFailure(new RuntimeException("no"));
        open();
        session.disconnect();
        last().listener.onClosed(1000, "");
        open();
        last().listener.onFailure(new RuntimeException("dropped"));

        assertTrue(events.size() > 15);
        for (Event e : events) {
            String event = e.name.substring(e.name.indexOf(':') + 1);
            assertTrue("undocumented event emitted: " + e.name, contract.containsKey(event));
            List<String> keys = new ArrayList<>(e.payload.keySet());
            keys.remove(event.equals("message") ? "binary" : "");
            Collections.sort(keys);
            assertEquals("payload keys of " + e.name, contract.get(event), keys);
        }
    }
}

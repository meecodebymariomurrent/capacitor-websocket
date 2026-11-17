package at.meecode.capacitorwebsocket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state machine of one named websocket: connection state, pending connect() handling, stale-event
 * protection and the event payloads delivered to JS.
 *
 * Deliberately free of Capacitor and OkHttp types so it can be unit-tested on the JVM. The plugin supplies
 * the real transport (OkHttp) and the event sink (Capacitor's notifyListeners).
 */
final class SocketSession {

    /** A single websocket connection attempt. Never reused after it closed. */
    interface Transport {
        /** Enqueues a text message; false if it could not be queued. */
        boolean send(String text);

        void close(int code);

        void cancel();
    }

    /** Callbacks from the transport. May arrive on any thread, even before {@link TransportFactory#open} returned. */
    interface TransportListener {
        void onOpen(Map<String, List<String>> headers);

        void onText(String text);

        void onBinary(String base64);

        void onClosing(int code, String reason);

        void onClosed(int code, String reason);

        void onFailure(Throwable error);
    }

    interface TransportFactory {
        Transport open(TransportListener listener);
    }

    /** Delivers an event to JS. {@code event} is the full name, e.g. "chat:message". */
    interface EventSink {
        void emit(String event, Map<String, Object> payload);
    }

    /** Settles the JS promise of a connect() call. */
    interface ConnectCallback {
        void resolve();

        void reject(String message, Exception cause);
    }

    static final int NORMAL_CLOSURE = 1000;

    final String name;
    private final TransportFactory factory;
    private final EventSink sink;

    private volatile Transport socket;
    private volatile boolean connected = false;
    private volatile boolean closedByClient = false;
    private volatile int generation = 0;
    private ConnectCallback pendingConnect;

    SocketSession(String name, TransportFactory factory, EventSink sink) {
        this.name = name;
        this.factory = factory;
        this.sink = sink;
    }

    boolean isConnected() {
        return connected;
    }

    // ---- API used by the plugin -------------------------------------------------------------------

    void connect(ConnectCallback callback) {
        synchronized (this) {
            if (connected) {
                callback.resolve();
                return;
            }
            if (pendingConnect != null) {
                callback.reject("Socket '" + name + "' is already connecting", null);
                return;
            }

            // A socket that is closing or dead is never reused; always start from a fresh one.
            detach();

            pendingConnect = callback;
            closedByClient = false;
            emitState("CONNECTING");
            int attempt = generation;
            Transport transport = factory.open(new AttemptListener(attempt));
            if (generation == attempt) {
                socket = transport;
            } else {
                // The attempt already failed (or was replaced) before open() returned.
                transport.cancel();
            }
        }
    }

    void disconnect() {
        Transport transport = null;
        boolean abortedConnect = false;
        synchronized (this) {
            if (connected && socket != null) {
                transport = socket;
                connected = false;
                closedByClient = true;
            } else if (socket != null) {
                // Still connecting: abort the attempt.
                detach();
                abortedConnect = true;
            }
        }

        if (abortedConnect) {
            settlePendingConnect("Connection attempt aborted", null);
        }
        if (transport != null) {
            emitState("CLOSING");
            transport.close(NORMAL_CLOSURE);
        }
    }

    /**
     * Sends a text message.
     *
     * @return null on success, otherwise the message to reject the JS call with
     */
    String send(String data) {
        Transport transport = socket;
        if (transport == null || !connected) {
            return "Socket '" + name + "' is not connected";
        }
        if (transport.send(data)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cause", "Message could not be enqueued (socket closing or queue full)");
        payload.put("frame", data);
        emit("senderror", payload);
        return "Couldnt send message";
    }

    /** Drops the connection without notifying JS and rejects a connect() that is still waiting. */
    void dispose() {
        synchronized (this) {
            detach();
        }
        settlePendingConnect("Connection attempt aborted", null);
    }

    // ---- internals ----------------------------------------------------------------------------------

    /** Drops the current socket (if any) without notifying JS. Callers must hold the lock. */
    private void detach() {
        Transport old = socket;
        generation++;
        socket = null;
        connected = false;
        if (old != null) {
            old.cancel();
        }
    }

    private void settlePendingConnect(String error, Exception cause) {
        ConnectCallback pending;
        synchronized (this) {
            pending = pendingConnect;
            pendingConnect = null;
        }
        if (pending == null) {
            return;
        }
        if (error == null) {
            pending.resolve();
        } else {
            pending.reject(error, cause);
        }
    }

    private void emit(String event, Map<String, Object> payload) {
        sink.emit(name + ":" + event, payload);
    }

    private void emitState(String state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", state);
        emit("statechange", payload);
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static String frame(int code, String reason) {
        return (code + " " + (reason == null ? "" : reason)).trim();
    }

    /** Listener of one connection attempt. Events from a replaced or detached attempt must not reach JS. */
    private final class AttemptListener implements TransportListener {
        private final int attempt;

        AttemptListener(int attempt) {
            this.attempt = attempt;
        }

        // A generation counter is used instead of comparing sockets because the transport may call back before
        // factory.open() has returned.
        private boolean isStale() {
            return generation != attempt;
        }

        @Override
        public void onOpen(Map<String, List<String>> headers) {
            if (isStale()) {
                return;
            }
            connected = true;
            closedByClient = false;
            emitState("OPEN");
            emit("connected", map("headers", headers));
            settlePendingConnect(null, null);
        }

        @Override
        public void onText(String text) {
            if (isStale()) {
                return;
            }
            emit("message", map("data", text));
        }

        @Override
        public void onBinary(String base64) {
            if (isStale()) {
                return;
            }
            // Same shape as iOS and web: binary frames are delivered base64 encoded.
            Map<String, Object> payload = map("data", base64);
            payload.put("binary", true);
            emit("message", payload);
        }

        @Override
        public void onClosing(int code, String reason) {
            if (isStale()) {
                return;
            }
            emit("closeframe", map("frame", frame(code, reason)));
            emitState("CLOSING");
        }

        @Override
        public void onClosed(int code, String reason) {
            if (isStale()) {
                return;
            }
            boolean byClient = closedByClient;
            connected = false;
            socket = null;
            generation++;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("closedByServer", !byClient);
            payload.put("serverCloseFrame", byClient ? "" : frame(code, reason));
            payload.put("clientCloseFrame", byClient ? frame(code, reason) : "");
            emitState("CLOSED");
            emit("disconnected", payload);
            settlePendingConnect("Connection closed before it was established", null);
        }

        @Override
        public void onFailure(Throwable error) {
            if (isStale()) {
                return;
            }
            boolean wasConnected = connected;
            connected = false;
            socket = null;
            generation++;

            if (!wasConnected) {
                emit("connecterror", map("exception", String.valueOf(error)));
                settlePendingConnect("Couldnt connect socket: " + error.getMessage(),
                        error instanceof Exception ? (Exception) error : new Exception(error));
            } else {
                emit("error", map("cause", String.valueOf(error)));
            }

            Map<String, Object> closed = new LinkedHashMap<>();
            closed.put("closedByServer", wasConnected);
            closed.put("serverCloseFrame", "");
            closed.put("clientCloseFrame", "");
            emitState("CLOSED");
            emit("disconnected", closed);
        }
    }
}

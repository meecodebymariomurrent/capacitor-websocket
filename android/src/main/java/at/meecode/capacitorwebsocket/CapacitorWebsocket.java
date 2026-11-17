package at.meecode.capacitorwebsocket;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

@CapacitorPlugin(name = "CapacitorWebsocket")
public class CapacitorWebsocket extends Plugin {
    private static final String TAG = "CapacitorWebsocket";
    private static final int NORMAL_CLOSURE = 1000;

    // Websockets must not have a read timeout; pings keep idle connections alive and detect dead peers.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build();

    private final Map<String, SocketConnection> sockets = new ConcurrentHashMap<>();

    @PluginMethod()
    public void build(PluginCall call) {
        String name = call.getString("name");
        if (name == null || name.isEmpty()) {
            call.reject("Must provide a socket name");
            return;
        }

        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Must provide a socket URL");
            return;
        }

        try {
            // OkHttp maps ws:// and wss:// to http:// and https:// internally.
            Request.Builder builder = new Request.Builder().url(url);

            JSObject headers = call.getObject("headers");
            if (headers != null) {
                for (Iterator<String> i = headers.keys(); i.hasNext(); ) {
                    String key = i.next();
                    builder.addHeader(key, String.valueOf(headers.get(key)));
                }
            }

            SocketConnection previous = sockets.put(name, new SocketConnection(name, builder.build()));
            if (previous != null) {
                previous.detach();
                settlePendingConnect(previous, "Connection attempt aborted", null);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Couldnt create socket: " + e.getMessage(), e);
        }
    }

    @PluginMethod()
    public void applyListeners(PluginCall call) {
        // Listeners are attached in connect(); kept for API compatibility with iOS and web.
        if (getSocket(call) == null) {
            return;
        }
        call.resolve();
    }

    @PluginMethod()
    public void connect(PluginCall call) {
        SocketConnection conn = getSocket(call);
        if (conn == null) {
            return;
        }

        synchronized (conn) {
            if (conn.connected) {
                call.resolve();
                return;
            }
            if (conn.pendingConnect != null) {
                call.reject("Socket '" + conn.name + "' is already connecting");
                return;
            }

            // A socket that is closing or dead is never reused; always start from a fresh one.
            conn.detach();

            conn.pendingConnect = call;
            getBridge().saveCall(call);
            emitState(conn, "CONNECTING");
            conn.socket = CLIENT.newWebSocket(conn.request, new Listener(conn, conn.generation));
        }
    }

    @PluginMethod()
    public void disconnect(PluginCall call) {
        SocketConnection conn = getSocket(call);
        if (conn == null) {
            return;
        }

        WebSocket ws;
        boolean abortedConnect = false;
        synchronized (conn) {
            ws = conn.socket;
            if (ws != null && conn.connected) {
                conn.connected = false;
                conn.closedByClient = true;
            } else if (ws != null) {
                // Still connecting: abort the attempt.
                conn.detach();
                ws = null;
                abortedConnect = true;
            }
        }
        if (abortedConnect) {
            settlePendingConnect(conn, "Connection attempt aborted", null);
        }

        if (ws != null) {
            emitState(conn, "CLOSING");
            ws.close(NORMAL_CLOSURE, null);
        }
        call.resolve();
    }

    @PluginMethod()
    public void send(PluginCall call) {
        SocketConnection conn = getSocket(call);
        if (conn == null) {
            return;
        }

        String data = call.getString("data");
        if (data == null) {
            call.reject("Must provide data to send");
            return;
        }

        WebSocket ws = conn.socket;
        if (ws == null || !conn.connected) {
            call.reject("Socket '" + conn.name + "' is not connected");
            return;
        }

        if (ws.send(data)) {
            call.resolve();
        } else {
            JSObject ret = new JSObject();
            ret.put("cause", "Message could not be enqueued (socket closing or queue full)");
            ret.put("frame", data);
            emit(conn, "senderror", ret);
            call.reject("Couldnt send message");
        }
    }

    @Override
    protected void handleOnDestroy() {
        for (SocketConnection conn : sockets.values()) {
            conn.detach();
            settlePendingConnect(conn, "Connection attempt aborted", null);
        }
        sockets.clear();
        super.handleOnDestroy();
    }

    private SocketConnection getSocket(PluginCall call) {
        String name = call.getString("name");
        if (name == null) {
            call.reject("Must provide a socket name");
            return null;
        }
        SocketConnection conn = sockets.get(name);
        if (conn == null) {
            call.reject(String.format("Socket '%s' doesnt exist", name));
        }
        return conn;
    }

    private void emit(SocketConnection conn, String event, JSObject data) {
        notifyListeners(conn.name + ":" + event, data);
    }

    private void emitState(SocketConnection conn, String state) {
        JSObject ret = new JSObject();
        ret.put("state", state);
        emit(conn, "statechange", ret);
    }

    private void settlePendingConnect(SocketConnection conn, String error, Exception cause) {
        PluginCall pending;
        synchronized (conn) {
            pending = conn.pendingConnect;
            conn.pendingConnect = null;
        }
        if (pending == null) {
            return;
        }
        if (error == null) {
            pending.resolve();
        } else {
            pending.reject(error, cause);
        }
        getBridge().releaseCall(pending);
    }

    private final class Listener extends WebSocketListener {
        private final SocketConnection conn;
        private final int generation;

        Listener(SocketConnection conn, int generation) {
            this.conn = conn;
            this.generation = generation;
        }

        /**
         * Events from a socket that was replaced or detached must not reach JS. A generation counter is used
         * instead of comparing sockets because OkHttp may call back before newWebSocket() has returned.
         */
        private boolean isStale(WebSocket ws) {
            return conn.generation != generation;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            if (isStale(ws)) {
                return;
            }
            conn.connected = true;
            conn.closedByClient = false;

            JSObject headers = new JSObject();
            for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
                headers.put(entry.getKey(), new JSArray(entry.getValue()));
            }
            JSObject ret = new JSObject();
            ret.put("headers", headers);

            emitState(conn, "OPEN");
            emit(conn, "connected", ret);
            settlePendingConnect(conn, null, null);
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            if (isStale(ws)) {
                return;
            }
            JSObject ret = new JSObject();
            ret.put("data", text);
            emit(conn, "message", ret);
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            if (isStale(ws)) {
                return;
            }
            // Same shape as iOS: binary frames are delivered base64 encoded.
            JSObject ret = new JSObject();
            ret.put("data", bytes.base64());
            ret.put("binary", true);
            emit(conn, "message", ret);
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            if (isStale(ws)) {
                return;
            }
            JSObject ret = new JSObject();
            ret.put("frame", (code + " " + reason).trim());
            emit(conn, "closeframe", ret);
            emitState(conn, "CLOSING");
            // Complete the closing handshake.
            ws.close(code, null);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            if (isStale(ws)) {
                return;
            }
            boolean byClient = conn.closedByClient;
            conn.connected = false;
            conn.socket = null;
            conn.generation++;

            JSObject ret = new JSObject();
            ret.put("closedByServer", !byClient);
            ret.put("serverCloseFrame", byClient ? "" : (code + " " + reason).trim());
            ret.put("clientCloseFrame", byClient ? (code + " " + reason).trim() : "");
            emitState(conn, "CLOSED");
            emit(conn, "disconnected", ret);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            if (isStale(ws)) {
                return;
            }
            Logger.error(TAG, "Socket '" + conn.name + "' failed: " + t, t);
            boolean wasConnected = conn.connected;
            conn.connected = false;
            conn.socket = null;
            conn.generation++;

            JSObject ret = new JSObject();
            if (!wasConnected) {
                ret.put("exception", String.valueOf(t));
                emit(conn, "connecterror", ret);
                settlePendingConnect(conn, "Couldnt connect socket: " + t.getMessage(),
                        t instanceof Exception ? (Exception) t : new Exception(t));
            } else {
                ret.put("cause", String.valueOf(t));
                emit(conn, "error", ret);
            }

            JSObject closed = new JSObject();
            closed.put("closedByServer", wasConnected);
            closed.put("serverCloseFrame", "");
            closed.put("clientCloseFrame", "");
            emitState(conn, "CLOSED");
            emit(conn, "disconnected", closed);
        }
    }

    private static final class SocketConnection {
        final String name;
        final Request request;
        volatile WebSocket socket;
        volatile boolean connected = false;
        volatile boolean closedByClient = false;
        volatile int generation = 0;
        PluginCall pendingConnect;

        SocketConnection(String name, Request request) {
            this.name = name;
            this.request = request;
        }

        /** Drops the current socket (if any) without notifying JS. */
        synchronized void detach() {
            WebSocket old = socket;
            generation++;
            socket = null;
            connected = false;
            if (old != null) {
                old.cancel();
            }
        }
    }
}

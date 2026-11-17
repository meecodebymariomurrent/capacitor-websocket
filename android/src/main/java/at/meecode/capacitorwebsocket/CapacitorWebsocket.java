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

    // Websockets must not have a read timeout; pings keep idle connections alive and detect dead peers.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build();

    private final Map<String, SocketSession> sockets = new ConcurrentHashMap<>();

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
            Request request = builder.build();

            SocketSession session = new SocketSession(
                    name,
                    listener -> OkHttpTransport.open(request, listener),
                    (event, payload) -> notifyListeners(event, toJs(payload)));

            SocketSession previous = sockets.put(name, session);
            if (previous != null) {
                previous.dispose();
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
        SocketSession session = getSocket(call);
        if (session == null) {
            return;
        }

        // Resolved once the socket is actually open, rejected if the attempt fails or is aborted.
        getBridge().saveCall(call);
        session.connect(new SocketSession.ConnectCallback() {
            @Override
            public void resolve() {
                call.resolve();
                getBridge().releaseCall(call);
            }

            @Override
            public void reject(String message, Exception cause) {
                call.reject(message, cause);
                getBridge().releaseCall(call);
            }
        });
    }

    @PluginMethod()
    public void disconnect(PluginCall call) {
        SocketSession session = getSocket(call);
        if (session == null) {
            return;
        }
        session.disconnect();
        call.resolve();
    }

    @PluginMethod()
    public void send(PluginCall call) {
        SocketSession session = getSocket(call);
        if (session == null) {
            return;
        }

        String data = call.getString("data");
        if (data == null) {
            call.reject("Must provide data to send");
            return;
        }

        String error = session.send(data);
        if (error == null) {
            call.resolve();
        } else {
            call.reject(error);
        }
    }

    @Override
    protected void handleOnDestroy() {
        for (SocketSession session : sockets.values()) {
            session.dispose();
        }
        sockets.clear();
        super.handleOnDestroy();
    }

    private SocketSession getSocket(PluginCall call) {
        String name = call.getString("name");
        if (name == null) {
            call.reject("Must provide a socket name");
            return null;
        }
        SocketSession session = sockets.get(name);
        if (session == null) {
            call.reject(String.format("Socket '%s' doesnt exist", name));
        }
        return session;
    }

    private static JSObject toJs(Map<String, Object> payload) {
        JSObject object = new JSObject();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            object.put(entry.getKey(), toJsValue(entry.getValue()));
        }
        return object;
    }

    @SuppressWarnings("unchecked")
    private static Object toJsValue(Object value) {
        if (value instanceof Map) {
            return toJs((Map<String, Object>) value);
        }
        if (value instanceof List) {
            JSArray array = new JSArray();
            for (Object item : (List<?>) value) {
                array.put(toJsValue(item));
            }
            return array;
        }
        return value;
    }

    /** Adapts an OkHttp WebSocket to the {@link SocketSession.Transport} the session works with. */
    private static final class OkHttpTransport extends WebSocketListener implements SocketSession.Transport {
        private final SocketSession.TransportListener listener;
        private volatile WebSocket webSocket;

        private OkHttpTransport(SocketSession.TransportListener listener) {
            this.listener = listener;
        }

        static OkHttpTransport open(Request request, SocketSession.TransportListener listener) {
            OkHttpTransport transport = new OkHttpTransport(listener);
            transport.webSocket = CLIENT.newWebSocket(request, transport);
            return transport;
        }

        @Override
        public boolean send(String text) {
            return webSocket.send(text);
        }

        @Override
        public void close(int code) {
            webSocket.close(code, null);
        }

        @Override
        public void cancel() {
            webSocket.cancel();
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            listener.onOpen(response.headers().toMultimap());
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            listener.onText(text);
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            listener.onBinary(bytes.base64());
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            listener.onClosing(code, reason);
            // Complete the closing handshake.
            ws.close(code, null);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            listener.onClosed(code, reason);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Logger.error(TAG, "WebSocket failed: " + t, t);
            listener.onFailure(t);
        }
    }
}

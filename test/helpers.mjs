// Minimal controllable WebSocket stand-in for testing the web implementation under Node.
export class FakeWebSocket {
  static instances = [];
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.sent = [];
    this.binaryType = "blob";
    this.onopen = null;
    this.onclose = null;
    this.onerror = null;
    this.onmessage = null;
    this._listeners = {};
    FakeWebSocket.instances.push(this);
  }

  addEventListener(type, fn, opts) {
    (this._listeners[type] ||= []).push({ fn, once: !!(opts && opts.once) });
  }

  _emit(type, ev) {
    const handler = this["on" + type];
    if (handler) handler(ev);
    for (const l of [...(this._listeners[type] || [])]) {
      l.fn(ev);
      if (l.once) this._listeners[type] = this._listeners[type].filter((x) => x !== l);
    }
  }

  send(data) {
    this.sent.push(data);
  }

  close(code = 1000, reason = "") {
    if (this.readyState === FakeWebSocket.CLOSED) return;
    this.readyState = FakeWebSocket.CLOSING;
    queueMicrotask(() => this.serverClose(code, reason));
  }

  // ---- test controls ----
  serverOpen() {
    this.readyState = FakeWebSocket.OPEN;
    this._emit("open", {});
  }
  serverMessage(data) {
    this._emit("message", { data });
  }
  serverClose(code = 1000, reason = "") {
    this.readyState = FakeWebSocket.CLOSED;
    this._emit("close", { code, reason });
  }
  serverError() {
    this._emit("error", {});
  }
}

export function installFakeWebSocket() {
  FakeWebSocket.instances = [];
  globalThis.WebSocket = FakeWebSocket;
}

/** Collects every event the plugin emits for a socket name. */
export function recordEvents(plugin, name, events) {
  const log = [];
  for (const e of events) {
    plugin.addListener(`${name}:${e}`, (payload) => log.push([e, payload]));
  }
  return log;
}

export const tick = () => new Promise((r) => setTimeout(r, 0));

export async function connected(plugin, name = "s", url = "ws://localhost:1") {
  await plugin.build({ name, url });
  const p = plugin.connect({ name });
  const ws = FakeWebSocket.instances.at(-1);
  ws.serverOpen();
  await p;
  return ws;
}

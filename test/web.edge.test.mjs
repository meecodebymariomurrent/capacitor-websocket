// Edge cases and the cross-platform event contract for the web implementation.
import test, { beforeEach, mock } from "node:test";
import assert from "node:assert/strict";

import { CapacitorWebsocketPluginWeb } from "../dist/esm/web.js";
import { FakeWebSocket, installFakeWebSocket, recordEvents, tick, connected } from "./helpers.mjs";

let plugin;
beforeEach(() => {
  installFakeWebSocket();
  plugin = new CapacitorWebsocketPluginWeb();
});

// ---- unknown sockets -------------------------------------------------------------------------

for (const method of ["connect", "disconnect", "applyListeners"]) {
  test(`${method}() rejects for an unknown socket name`, async () => {
    await assert.rejects(plugin[method]({ name: "nope" }), /Couldnt get socket with name: nope/);
  });
}

test("send() rejects for an unknown socket name", async () => {
  await assert.rejects(plugin.send({ name: "nope", data: "x" }), /Couldnt get socket with name: nope/);
});

// ---- lifecycle edge cases --------------------------------------------------------------------

test("disconnect() twice rejects the second call", async () => {
  await connected(plugin);
  await plugin.disconnect({ name: "s" });
  await assert.rejects(plugin.disconnect({ name: "s" }), /already disconnected/);
});

test("send() rejects after the server closed the connection", async () => {
  const ws = await connected(plugin);
  ws.serverClose(1001, "bye");
  await assert.rejects(plugin.send({ name: "s", data: "x" }), /disconnected socket/);
});

test("send() rejects while the socket is closing after disconnect()", async () => {
  await connected(plugin);
  await plugin.disconnect({ name: "s" });
  await assert.rejects(plugin.send({ name: "s", data: "x" }), /disconnected socket/);
});

test("reconnecting before the old socket finished closing ignores the old socket's late events", async () => {
  const log = recordEvents(plugin, "s", ["disconnected", "message", "statechange"]);
  const first = await connected(plugin);
  await plugin.disconnect({ name: "s" }); // old socket is still CLOSING (fake closes on a microtask)
  const p = plugin.connect({ name: "s" });
  const second = FakeWebSocket.instances.at(-1);
  second.serverOpen();
  await p;
  log.length = 0;

  first.serverClose(1000, "late"); // must not flip the new connection to disconnected
  first.serverMessage("late");
  await tick();

  assert.deepEqual(log, []);
  await plugin.send({ name: "s", data: "still works" });
  assert.deepEqual(second.sent, ["still works"]);
});

test("sockets are independent of each other", async () => {
  const wsA = await connected(plugin, "a");
  const wsB = await connected(plugin, "b");
  await plugin.disconnect({ name: "a" });
  await tick();
  assert.equal(wsA.readyState, FakeWebSocket.CLOSED);
  assert.equal(wsB.readyState, FakeWebSocket.OPEN);
  await plugin.send({ name: "b", data: "x" });
  assert.deepEqual(wsB.sent, ["x"]);
});

test("closing from the client does not emit :closeframe", async () => {
  const log = recordEvents(plugin, "s", ["closeframe"]);
  await connected(plugin);
  await plugin.disconnect({ name: "s" });
  await tick();
  assert.deepEqual(log, []);
});

test("an error after connecting emits :error and the following close still emits :disconnected", async () => {
  const log = recordEvents(plugin, "s", ["error", "disconnected"]);
  const ws = await connected(plugin);
  ws.serverError();
  ws.serverClose(1006, "");
  assert.deepEqual(log.map(([e]) => e), ["error", "disconnected"]);
  assert.equal(log[1][1].closedByServer, true);
});

test("a failed connect followed by the browser's close event emits :disconnected with closedByServer=false", async () => {
  const log = recordEvents(plugin, "s", ["disconnected"]);
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  const p = plugin.connect({ name: "s" });
  const ws = FakeWebSocket.instances[0];
  ws.serverError();
  await assert.rejects(p);
  ws.serverClose(1006, "");
  assert.deepEqual(log[0][1], { closedByServer: false, serverCloseFrame: "", clientCloseFrame: "" });
});

test("the socket can be connected again after a failed attempt", async () => {
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  const first = plugin.connect({ name: "s" });
  FakeWebSocket.instances[0].serverError();
  await assert.rejects(first);
  const second = plugin.connect({ name: "s" });
  FakeWebSocket.instances[1].serverOpen();
  await second;
});

// ---- timers ----------------------------------------------------------------------------------

test("the connect timeout is cancelled once the socket opened", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const log = recordEvents(plugin, "s", ["connecterror", "disconnected"]);
    await plugin.build({ name: "s", url: "ws://localhost:1" });
    const p = plugin.connect({ name: "s" });
    FakeWebSocket.instances[0].serverOpen();
    await p;
    mock.timers.tick(60000);
    assert.deepEqual(log, []);
    assert.equal(FakeWebSocket.instances[0].readyState, FakeWebSocket.OPEN);
  } finally {
    mock.timers.reset();
  }
});

test("the connect timeout is cancelled after a failed attempt", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const log = recordEvents(plugin, "s", ["connecterror"]);
    await plugin.build({ name: "s", url: "ws://localhost:1" });
    const p = plugin.connect({ name: "s" });
    FakeWebSocket.instances[0].serverError();
    await assert.rejects(p);
    mock.timers.tick(60000);
    assert.equal(log.length, 1, "connecterror must be emitted exactly once");
  } finally {
    mock.timers.reset();
  }
});

test("a timed out attempt closes its socket and can be retried", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await plugin.build({ name: "s", url: "ws://localhost:1" });
    const first = plugin.connect({ name: "s" });
    mock.timers.tick(5000);
    await assert.rejects(first, /timeout/i);
    assert.notEqual(FakeWebSocket.instances[0].readyState, FakeWebSocket.OPEN);
    const second = plugin.connect({ name: "s" });
    FakeWebSocket.instances[1].serverOpen();
    await second;
  } finally {
    mock.timers.reset();
  }
});

// ---- payloads --------------------------------------------------------------------------------

test("messages are delivered in order, including empty strings", async () => {
  const log = recordEvents(plugin, "s", ["message"]);
  const ws = await connected(plugin);
  ws.serverMessage("1");
  ws.serverMessage("");
  ws.serverMessage("3");
  assert.deepEqual(log.map(([, p]) => p.data), ["1", "", "3"]);
});

test("large binary messages are base64 encoded correctly", async () => {
  const log = recordEvents(plugin, "s", ["message"]);
  const ws = await connected(plugin);
  const bytes = new Uint8Array(200_000).map((_, i) => i % 251);
  ws.serverMessage(bytes.buffer);
  assert.equal(log[0][1].data, Buffer.from(bytes).toString("base64"));
  assert.equal(log[0][1].binary, true);
});

test("an empty binary message is delivered as an empty base64 string", async () => {
  const log = recordEvents(plugin, "s", ["message"]);
  const ws = await connected(plugin);
  ws.serverMessage(new ArrayBuffer(0));
  assert.deepEqual(log[0][1], { data: "", binary: true });
});

test("the socket opens the URL given to build()", async () => {
  await plugin.build({ name: "s", url: "wss://example.test/path?x=1" });
  const p = plugin.connect({ name: "s" });
  assert.equal(FakeWebSocket.instances[0].url, "wss://example.test/path?x=1");
  FakeWebSocket.instances[0].serverOpen();
  await p;
});

// ---- event contract (must hold on every platform) --------------------------------------------

const CONTRACT = {
  connected: ["headers"],
  message: ["data"], // plus optional "binary"
  disconnected: ["closedByServer", "serverCloseFrame", "clientCloseFrame"],
  statechange: ["state"],
  closeframe: ["frame"],
  connecterror: ["exception"],
  error: ["cause"],
};

test("every emitted event is part of the documented contract and has the documented payload keys", async () => {
  const seen = [];
  const original = plugin.notifyListeners.bind(plugin);
  plugin.notifyListeners = (name, data, ...rest) => {
    seen.push([name, data]);
    return original(name, data, ...rest);
  };

  // happy path + binary + server close + failed connect + client disconnect
  const ws = await connected(plugin);
  ws.serverMessage("t");
  ws.serverMessage(new Uint8Array([1]).buffer);
  ws.serverError();
  ws.serverClose(1001, "bye");
  await plugin.build({ name: "f", url: "ws://localhost:1" });
  const failing = plugin.connect({ name: "f" });
  FakeWebSocket.instances.at(-1).serverError();
  await assert.rejects(failing);
  await connected(plugin, "c");
  await plugin.disconnect({ name: "c" });
  await tick();

  assert.ok(seen.length > 10);
  for (const [fullName, data] of seen) {
    const event = fullName.split(":")[1];
    assert.ok(event in CONTRACT, `undocumented event emitted: ${fullName}`);
    const keys = Object.keys(data).filter((k) => !(event === "message" && k === "binary"));
    assert.deepEqual(keys.sort(), [...CONTRACT[event]].sort(), `payload keys of ${fullName}`);
  }
  const states = new Set(seen.filter(([n]) => n.endsWith(":statechange")).map(([, d]) => d.state));
  assert.deepEqual([...states].sort(), ["CLOSED", "CLOSING", "CONNECTING", "OPEN"]);
});

// ---- a socket that is closing keeps delivering events until its close event arrives -----------

test("a replaced socket that is still closing cannot deliver messages or a close event", async () => {
  FakeWebSocket.autoClose = false;
  const log = recordEvents(plugin, "s", ["message", "disconnected", "error", "statechange"]);
  const first = await connected(plugin, "s", "ws://one");
  await plugin.build({ name: "s", url: "ws://two" });
  log.length = 0;

  first.serverMessage("late");
  first.serverError();
  first.serverClose(1000, "late");
  await tick();

  assert.deepEqual(log, []);
});

test("an old socket still closing cannot disturb the connection that replaced it", async () => {
  FakeWebSocket.autoClose = false;
  const log = recordEvents(plugin, "s", ["message", "disconnected", "error", "statechange"]);
  const first = await connected(plugin);
  await plugin.disconnect({ name: "s" }); // CLOSING, close event not delivered yet
  const p = plugin.connect({ name: "s" });
  const second = FakeWebSocket.instances.at(-1);
  second.serverOpen();
  await p;
  log.length = 0;

  first.serverMessage("late");
  first.serverClose(1000, "late");
  await tick();

  assert.deepEqual(log, []);
  await plugin.send({ name: "s", data: "ok" });
  assert.deepEqual(second.sent, ["ok"]);
});

test("a timed out attempt cannot be revived by its socket opening late", async () => {
  FakeWebSocket.autoClose = false;
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const log = recordEvents(plugin, "s", ["connected", "statechange"]);
    await plugin.build({ name: "s", url: "ws://localhost:1" });
    const p = plugin.connect({ name: "s" });
    mock.timers.tick(5000);
    await assert.rejects(p, /timeout/i);
    log.length = 0;

    FakeWebSocket.instances[0].serverOpen(); // the abandoned socket opens after all
    assert.deepEqual(log, []);
    await assert.rejects(plugin.send({ name: "s", data: "x" }), /disconnected socket/);
  } finally {
    mock.timers.reset();
  }
});

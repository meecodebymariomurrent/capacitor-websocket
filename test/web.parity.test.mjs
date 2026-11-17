// Behaviour that must be identical on web, Android and iOS.
import test, { beforeEach, mock } from "node:test";
import assert from "node:assert/strict";

import { CapacitorWebsocketPluginWeb } from "../dist/esm/web.js";
import { FakeWebSocket, installFakeWebSocket, recordEvents, tick, connected } from "./helpers.mjs";

let plugin;
beforeEach(() => {
  installFakeWebSocket();
  plugin = new CapacitorWebsocketPluginWeb();
});

test("connect() on an already connected socket resolves without opening a second socket", async () => {
  await connected(plugin);
  await plugin.connect({ name: "s" });
  assert.equal(FakeWebSocket.instances.length, 1);
});

test("a socket can be connected again after disconnect()", async () => {
  await connected(plugin);
  await plugin.disconnect({ name: "s" });
  await tick();
  const p = plugin.connect({ name: "s" });
  assert.equal(FakeWebSocket.instances.length, 2);
  FakeWebSocket.instances[1].serverOpen();
  await p;
  await plugin.send({ name: "s", data: "again" });
  assert.deepEqual(FakeWebSocket.instances[1].sent, ["again"]);
});

test("a socket can be connected again after the server closed it", async () => {
  const ws = await connected(plugin);
  ws.serverClose(1001, "bye");
  const p = plugin.connect({ name: "s" });
  FakeWebSocket.instances[1].serverOpen();
  await p;
});

test("build() with an existing name replaces the socket and closes the old one", async () => {
  const first = await connected(plugin, "s", "ws://one");
  await plugin.build({ name: "s", url: "ws://two" });
  await tick();
  assert.equal(first.readyState, FakeWebSocket.CLOSED);
  const p = plugin.connect({ name: "s" });
  assert.equal(FakeWebSocket.instances.at(-1).url, "ws://two");
  FakeWebSocket.instances.at(-1).serverOpen();
  await p;
});

test("events from a replaced socket are ignored", async () => {
  const log = recordEvents(plugin, "s", ["message", "disconnected"]);
  const first = await connected(plugin, "s", "ws://one");
  await plugin.build({ name: "s", url: "ws://two" });
  log.length = 0;
  first.serverMessage("stale");
  await tick();
  assert.deepEqual(log.filter(([e]) => e === "message"), []);
});

test("statechange follows CONNECTING -> OPEN -> CLOSING -> CLOSED", async () => {
  const log = recordEvents(plugin, "s", ["statechange"]);
  await connected(plugin);
  await plugin.disconnect({ name: "s" });
  await tick();
  assert.deepEqual(log.map(([, p]) => p.state), ["CONNECTING", "OPEN", "CLOSING", "CLOSED"]);
});

test("a failed connect emits :connecterror and rejects", async () => {
  const log = recordEvents(plugin, "s", ["connecterror", "error"]);
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  const p = plugin.connect({ name: "s" });
  FakeWebSocket.instances[0].serverError();
  await assert.rejects(p);
  assert.deepEqual(log.map(([e]) => e), ["connecterror"]);
  assert.ok(typeof log[0][1].exception === "string");
});

test("connect times out after 5 seconds and rejects", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await plugin.build({ name: "s", url: "ws://localhost:1" });
    const p = plugin.connect({ name: "s" });
    mock.timers.tick(5000);
    await assert.rejects(p, /timeout/i);
  } finally {
    mock.timers.reset();
  }
});

test("disconnected frames use the '<code> <reason>' format on both sides", async () => {
  const log = recordEvents(plugin, "s", ["disconnected"]);
  const ws = await connected(plugin);
  ws.serverClose(1001, "bye");
  assert.deepEqual(log[0][1], { closedByServer: true, serverCloseFrame: "1001 bye", clientCloseFrame: "" });
});

test("client-initiated close reports the frame as clientCloseFrame", async () => {
  const log = recordEvents(plugin, "s", ["disconnected"]);
  await connected(plugin);
  await plugin.disconnect({ name: "s" });
  await tick();
  assert.equal(log[0][1].closedByServer, false);
  assert.equal(log[0][1].serverCloseFrame, "");
  assert.match(log[0][1].clientCloseFrame, /^1000/);
});

test("a server close emits :closeframe before :disconnected", async () => {
  const log = recordEvents(plugin, "s", ["closeframe", "disconnected"]);
  const ws = await connected(plugin);
  ws.serverClose(1001, "bye");
  assert.deepEqual(log.map(([e]) => e), ["closeframe", "disconnected"]);
  assert.equal(log[0][1].frame, "1001 bye");
});

test("binary messages are delivered base64 encoded with binary=true", async () => {
  const log = recordEvents(plugin, "s", ["message"]);
  const ws = await connected(plugin);
  assert.equal(ws.binaryType, "arraybuffer");
  ws.serverMessage(new Uint8Array([1, 2, 3]).buffer);
  assert.deepEqual(log, [["message", { data: "AQID", binary: true }]]);
});

test("text messages carry no binary flag", async () => {
  const log = recordEvents(plugin, "s", ["message"]);
  const ws = await connected(plugin);
  ws.serverMessage("hi");
  assert.deepEqual(log[0][1], { data: "hi" });
});

test("send() rejects non-string data like the native platforms do", async () => {
  await connected(plugin);
  await assert.rejects(plugin.send({ name: "s", data: { a: 1 } }), /Must provide data to send/);
});

test("connected event carries a headers object", async () => {
  const log = recordEvents(plugin, "s", ["connected"]);
  await connected(plugin);
  assert.deepEqual(log[0][1], { headers: {} });
});

test("disconnect() while connecting aborts the attempt and rejects the pending connect()", async () => {
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  const p = plugin.connect({ name: "s" });
  await plugin.disconnect({ name: "s" });
  await assert.rejects(p, /aborted/i);
  // and the socket is usable again afterwards
  const again = plugin.connect({ name: "s" });
  FakeWebSocket.instances.at(-1).serverOpen();
  await again;
});

test("build() while connecting rejects the pending connect()", async () => {
  await plugin.build({ name: "s", url: "ws://one" });
  const p = plugin.connect({ name: "s" });
  await plugin.build({ name: "s", url: "ws://two" });
  await assert.rejects(p, /aborted/i);
});

test("a second connect() while connecting is rejected", async () => {
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  const first = plugin.connect({ name: "s" });
  await assert.rejects(plugin.connect({ name: "s" }), /already connecting/);
  FakeWebSocket.instances[0].serverOpen();
  await first;
});

import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";

import { CapacitorWebsocketPluginWeb } from "../dist/esm/web.js";
import { FakeWebSocket, installFakeWebSocket, recordEvents, tick, connected } from "./helpers.mjs";

let plugin;
beforeEach(() => {
  installFakeWebSocket();
  plugin = new CapacitorWebsocketPluginWeb();
});

test("connect() resolves once the socket opens and emits :connected", async () => {
  const log = recordEvents(plugin, "s", ["connected"]);
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  const p = plugin.connect({ name: "s" });
  assert.equal(FakeWebSocket.instances.length, 1);
  assert.equal(FakeWebSocket.instances[0].url, "ws://localhost:1");
  FakeWebSocket.instances[0].serverOpen();
  await p;
  assert.equal(log.length, 1);
  assert.equal(log[0][0], "connected");
});

test("connect() rejects for an unknown socket name", async () => {
  await assert.rejects(plugin.connect({ name: "nope" }), /Couldnt get socket/);
});

test("connect() rejects when the connection fails", async () => {
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  const p = plugin.connect({ name: "s" });
  FakeWebSocket.instances[0].serverError();
  await assert.rejects(p, /Couldnt build websocket connection/);
});

test("incoming messages are delivered as :message", async () => {
  const log = recordEvents(plugin, "s", ["message"]);
  const ws = await connected(plugin);
  ws.serverMessage("hello");
  assert.deepEqual(log, [["message", { data: "hello" }]]);
});

test("events are namespaced per socket name", async () => {
  const a = recordEvents(plugin, "a", ["message"]);
  const b = recordEvents(plugin, "b", ["message"]);
  const wsA = await connected(plugin, "a");
  await connected(plugin, "b");
  wsA.serverMessage("only-a");
  assert.equal(a.length, 1);
  assert.equal(b.length, 0);
});

test("send() writes to the socket", async () => {
  const ws = await connected(plugin);
  await plugin.send({ name: "s", data: "ping" });
  assert.deepEqual(ws.sent, ["ping"]);
});

test("send() rejects when the socket is not connected", async () => {
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  await assert.rejects(plugin.send({ name: "s", data: "x" }), /disconnected socket/);
});

test("server-initiated close emits :disconnected with closedByServer=true", async () => {
  const log = recordEvents(plugin, "s", ["disconnected"]);
  const ws = await connected(plugin);
  ws.serverClose(1001, "bye");
  assert.equal(log.length, 1);
  assert.equal(log[0][1].closedByServer, true);
});

test("disconnect() closes the socket and emits :disconnected with closedByServer=false", async () => {
  const log = recordEvents(plugin, "s", ["disconnected"]);
  const ws = await connected(plugin);
  await plugin.disconnect({ name: "s" });
  await tick();
  assert.equal(ws.readyState, FakeWebSocket.CLOSED);
  assert.equal(log.length, 1);
  assert.equal(log[0][1].closedByServer, false);
});

test("disconnect() rejects when not connected", async () => {
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  await assert.rejects(plugin.disconnect({ name: "s" }), /already disconnected/);
});

test("socket errors after connecting are reported as :error", async () => {
  const log = recordEvents(plugin, "s", ["error"]);
  const ws = await connected(plugin);
  ws.serverError();
  assert.equal(log.length, 1);
  assert.ok(typeof log[0][1].cause === "string");
});

test("applyListeners() is a no-op that resolves", async () => {
  await plugin.build({ name: "s", url: "ws://localhost:1" });
  await assert.doesNotReject(plugin.applyListeners({ name: "s" }));
});

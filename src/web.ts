import { WebPlugin } from "@capacitor/core";

import type {
    BuildOptions,
    CapacitorWebsocketPlugin,
    OptionsBase,
    SendOptions,
    SocketMetadata,
} from "./definitions.js";

const CONNECT_TIMEOUT_MS = 5000;
const NORMAL_CLOSURE = 1000;

interface SocketEntry extends SocketMetadata {
    /** Bumped whenever the underlying socket is replaced, so late events from old sockets can be ignored. */
    generation: number;
    connecting: boolean;
    closedByClient: boolean;
    /** Settles a pending connect() promise when the attempt is aborted (disconnect, rebuild). */
    abortConnect: ((error: Error) => void) | null;
}

export class CapacitorWebsocketPluginWeb extends WebPlugin implements CapacitorWebsocketPlugin {
    private sockets = new Map<string, SocketEntry>();

    async build(options: BuildOptions): Promise<void> {
        const existing = this.sockets.get(options.name);
        if (existing) {
            this.detach(existing);
        }
        // Custom headers cannot be set on browser WebSockets and are ignored on web.
        this.sockets.set(options.name, {
            socket: null,
            name: options.name,
            url: options.url,
            connected: false,
            generation: 0,
            connecting: false,
            closedByClient: false,
            abortConnect: null,
        });
    }

    async applyListeners(_options: OptionsBase): Promise<void> {
        // Nothing to do - listeners are attached during connect().
        this.getEntry(_options.name);
    }

    async connect(options: OptionsBase): Promise<void> {
        const entry = this.getEntry(options.name);

        if (entry.connected) {
            return;
        }
        if (entry.connecting) {
            throw new Error(`Socket '${entry.name}' is already connecting`);
        }

        // A socket that is closing or dead is never reused; always start from a fresh one.
        this.detach(entry);

        const ws = new WebSocket(entry.url);
        ws.binaryType = "arraybuffer";
        const generation = ++entry.generation;
        const isStale = () => entry.generation !== generation;
        let opened = false;

        entry.socket = ws;
        entry.connecting = true;
        entry.closedByClient = false;
        this.emitState(entry.name, "CONNECTING");

        ws.onmessage = (ev: MessageEvent) => {
            if (isStale()) return;
            if (typeof ev.data === "string") {
                this.notifyListeners(`${entry.name}:message`, { data: ev.data });
            } else if (ev.data instanceof ArrayBuffer) {
                this.notifyListeners(`${entry.name}:message`, { data: toBase64(ev.data), binary: true });
            }
        };

        ws.onclose = (ev: CloseEvent) => {
            if (isStale()) return;
            const byClient = entry.closedByClient;
            const frame = `${ev.code} ${ev.reason}`.trim();

            entry.connected = false;
            entry.connecting = false;
            entry.socket = null;
            entry.generation++;

            if (opened && !byClient) {
                this.notifyListeners(`${entry.name}:closeframe`, { frame });
            }
            this.emitState(entry.name, "CLOSED");
            this.notifyListeners(`${entry.name}:disconnected`, {
                closedByServer: opened && !byClient,
                serverCloseFrame: opened && !byClient ? frame : "",
                clientCloseFrame: opened && byClient ? frame : "",
            });
        };

        await new Promise<void>((resolve, reject) => {
            const timer = setTimeout(() => {
                if (isStale()) return;
                entry.abortConnect = null;
                this.detach(entry);
                this.notifyListeners(`${entry.name}:connecterror`, { exception: "Socket timeout (5s)" });
                this.emitState(entry.name, "CLOSED");
                reject(new Error("Socket timeout (5s)"));
            }, CONNECT_TIMEOUT_MS);

            entry.abortConnect = (error) => {
                clearTimeout(timer);
                entry.abortConnect = null;
                reject(error);
            };

            ws.onopen = () => {
                if (isStale()) return;
                clearTimeout(timer);
                entry.abortConnect = null;
                opened = true;
                entry.connected = true;
                entry.connecting = false;
                this.emitState(entry.name, "OPEN");
                this.notifyListeners(`${entry.name}:connected`, { headers: {} });
                resolve();
            };

            ws.onerror = () => {
                if (isStale()) return;
                if (opened) {
                    this.notifyListeners(`${entry.name}:error`, { cause: "WebSocket error" });
                    return;
                }
                clearTimeout(timer);
                entry.abortConnect = null;
                entry.connecting = false;
                this.notifyListeners(`${entry.name}:connecterror`, { exception: "WebSocket connection failed" });
                reject(new Error("Couldnt build websocket connection with name: " + entry.name));
            };
        });
    }

    async disconnect(options: OptionsBase): Promise<void> {
        const entry = this.getEntry(options.name);

        if (entry.connecting) {
            // Abort the attempt that is still in flight.
            this.detach(entry);
            return;
        }
        if (!entry.connected || !entry.socket) {
            throw new Error("Tried to disconnect an already disconnected or not yet connected socket!");
        }

        entry.connected = false;
        entry.closedByClient = true;
        this.emitState(entry.name, "CLOSING");
        entry.socket.close(NORMAL_CLOSURE);
    }

    async send(options: SendOptions): Promise<void> {
        if (typeof options.data !== "string") {
            throw new Error("Must provide data to send");
        }
        const entry = this.getEntry(options.name);

        if (!entry.connected || !entry.socket) {
            throw new Error("Tried to send to a disconnected socket!");
        }

        entry.socket.send(options.data);
    }

    private getEntry(name: string): SocketEntry {
        const entry = this.sockets.get(name);
        if (!entry) {
            throw new Error("Couldnt get socket with name: " + name);
        }
        return entry;
    }

    private emitState(name: string, state: string): void {
        this.notifyListeners(`${name}:statechange`, { state });
    }

    /** Drops the current socket (if any) without notifying listeners and settles a pending connect(). */
    private detach(entry: SocketEntry): void {
        const old = entry.socket;
        const abort = entry.abortConnect;

        entry.generation++;
        entry.socket = null;
        entry.connected = false;
        entry.connecting = false;
        entry.abortConnect = null;

        if (old) {
            old.onopen = old.onclose = old.onerror = old.onmessage = null;
            try {
                old.close();
            } catch {
                // already closed
            }
        }
        abort?.(new Error("Connection attempt aborted"));
    }
}

function toBase64(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    const chunk = 0x8000;
    let binary = "";
    for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
}

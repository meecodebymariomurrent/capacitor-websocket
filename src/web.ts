import { WebPlugin } from "@capacitor/core";

import type {
    BuildOptions,
    CapacitorWebsocketPlugin,
    OptionsBase,
    SendOptions,
    SocketMetadata,
} from "./definitions.js";

export class CapacitorWebsocketPluginWeb extends WebPlugin implements CapacitorWebsocketPlugin {
    private sockets: SocketMetadata[] = [];

    async connect(options: OptionsBase): Promise<void> {
        const sIndex = this.getSocketMetadataIndex(options.name);
        const sockMeta = this.getSocketMetadata(sIndex);

        console.log(sockMeta.name + " is connecting");
        const s = new WebSocket(sockMeta.url);

        s.onclose = (ev: CloseEvent) => {
            console.log(`${sockMeta.name} is closing.`);
            this.notifyListeners(`${options.name}:disconnected`, {
                closedByServer: sockMeta.connected, // disconnect() clears the flag before closing
                serverCloseFrame: ev.reason,
                clientCloseFrame: "",
            });
            sockMeta.connected = false;
            sockMeta.socket = null;
        };

        s.onerror = () => {
            this.notifyListeners(`${options.name}:error`, { cause: "WebSocket error" });
        };

        s.onmessage = (ev: MessageEvent) => {
            this.notifyListeners(`${options.name}:message`, { data: ev.data });
        };

        // Wait for the socket to open (or fail / time out) instead of polling readyState.
        await new Promise<void>((resolve, reject) => {
            const timer = setTimeout(() => {
                s.close();
                reject(new Error("Socket timeout (10s)"));
            }, 10000);

            s.onopen = () => {
                clearTimeout(timer);
                resolve();
            };
            s.addEventListener("error", () => {
                clearTimeout(timer);
                reject(new Error("Couldnt build websocket connection with name: " + options.name));
            }, { once: true });
        });

        console.log(`${sockMeta.name} connected.`);
        sockMeta.connected = true;
        sockMeta.socket = s;
        this.notifyListeners(`${options.name}:connected`, { headers: {} });
    }

    async disconnect(options: OptionsBase): Promise<void> {
        const sIndex = this.getSocketMetadataIndex(options.name);
        const sockMeta = this.getSocketMetadata(sIndex);

        if (!sockMeta.connected || !sockMeta.socket) {
            throw new Error("Tried to disconnect an already disconnected or not yet connected socket!");
        }

        sockMeta.connected = false;
        sockMeta.socket.close();
        sockMeta.socket = null;

        this.sockets.splice(sIndex, 1);
        return;
    }

    async send(options: SendOptions): Promise<void> {
        const sIndex = this.getSocketMetadataIndex(options.name);
        const sockMeta = this.getSocketMetadata(sIndex);

        if (!sockMeta.connected || !sockMeta.socket) {
            throw new Error("Tried to send to a disconnected socket!");
        }

        sockMeta.socket.send(options.data);
        return;
    }

    async applyListeners(_options: OptionsBase): Promise<void> {
        // do nothing - we apply listeners during connect phase
    }

    async build(options: BuildOptions): Promise<void> {
        const entry = { socket: null, name: options.name, url: options.url, connected: false };
        this.sockets.push(entry);
    }

    private verifySocket(name: string): SocketMetadata {
        const socketToFind = this.sockets.find(x => x.name === name);
        if (!socketToFind) {
            throw new Error("Couldnt get socket with name: " + name);
        }
        return socketToFind;
    }

    private getSocketMetadata(sIndex: number): SocketMetadata {
        return this.sockets[sIndex];
    }

    private getSocketMetadataIndex(name: string): number {
        const socketToFind = this.verifySocket(name);
        return this.sockets.indexOf(socketToFind);
    }
}

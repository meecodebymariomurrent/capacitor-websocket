import { registerPlugin } from "@capacitor/core";

import type { CapacitorWebsocketPlugin } from "./definitions.js";

const CapacitorWebsocket = registerPlugin<CapacitorWebsocketPlugin>("CapacitorWebsocket", {
    web: () => import("./web.js").then(m => new m.CapacitorWebsocketPluginWeb())
});

export * from "./definitions.js";
export { CapacitorWebsocket };

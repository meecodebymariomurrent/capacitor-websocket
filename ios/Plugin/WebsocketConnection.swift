//
//  WebsocketConnection.swift
//  Plugin
//
//  Created by Ryan Cockram on 2023-03-16.
//

import Foundation
import Capacitor
import Starscream

/// The subset of Starscream's `WebSocket` the plugin relies on. Lets tests drive the connection with a mock.
protocol SocketTransport: AnyObject {
    var onEvent: ((WebSocketEvent) -> Void)? { get set }
    func connect()
    func disconnect(closeCode: UInt16)
    func forceDisconnect()
    func write(string: String, completion: (() -> Void)?)
}

extension Starscream.WebSocket: SocketTransport {}

/// One named websocket. Emits the same events, with the same payloads, as the Android and web implementations.
class SocketConnection {
    static let normalClosure: UInt16 = 1000

    let name: String
    private(set) var connected: Bool = false
    private(set) var socket: SocketTransport?

    /// Seconds to wait for the connection to open before giving up.
    var connectTimeout: TimeInterval = 5
    /// Creates the transport for each connection attempt. A closed socket is never reused.
    var makeTransport: (URLRequest) -> SocketTransport = { Starscream.WebSocket(request: $0) }
    /// Delivers an event (full name, payload) to JS. Replaceable in tests.
    var emit: (String, [String: Any]) -> Void

    private let request: URLRequest
    private weak var pluginContext: CAPPlugin?
    private var pendingConnect: CAPPluginCall?
    private var closedByClient = false
    private var generation = 0
    private var timeout: DispatchWorkItem?

    init(name: String, plugin: CAPPlugin, request: URLRequest) {
        self.name = name
        self.pluginContext = plugin
        self.request = request
        self.emit = { [weak plugin] event, data in
            plugin?.notifyListeners(event, data: data)
        }
    }

    // MARK: - Public API

    func connect(_ call: CAPPluginCall) {
        if connected {
            call.resolve()
            return
        }
        if pendingConnect != nil {
            call.reject("Socket '\(name)' is already connecting")
            return
        }

        // A socket that is closing or dead is never reused; always start from a fresh one.
        detach()

        generation += 1
        let attempt = generation
        let transport = makeTransport(request)
        transport.onEvent = { [weak self] event in
            self?.handle(event, generation: attempt)
        }
        socket = transport
        pendingConnect = call
        closedByClient = false

        let item = DispatchWorkItem { [weak self] in
            self?.connectTimedOut(generation: attempt)
        }
        timeout = item
        DispatchQueue.main.asyncAfter(deadline: .now() + connectTimeout, execute: item)

        emitState("CONNECTING")
        transport.connect()
    }

    func disconnect(_ call: CAPPluginCall) {
        if pendingConnect != nil {
            // Abort the attempt that is still in flight.
            detach()
            settlePendingConnect(error: "Connection attempt aborted")
        } else if connected, let transport = socket {
            connected = false
            closedByClient = true
            emitState("CLOSING")
            transport.disconnect(closeCode: SocketConnection.normalClosure)
        }
        call.resolve()
    }

    /// Kept for API compatibility: listeners are attached per connection attempt in `connect`.
    func bind() {}

    /// Drops the current socket (if any) without notifying JS.
    func detach() {
        generation += 1
        timeout?.cancel()
        timeout = nil
        connected = false
        let old = socket
        socket = nil
        old?.onEvent = nil
        old?.forceDisconnect()
    }

    /// Drops the socket and rejects a connect() that is still waiting. Used when the connection is replaced.
    func dispose() {
        detach()
        settlePendingConnect(error: "Connection attempt aborted")
    }

    // MARK: - Event handling

    func handle(_ event: WebSocketEvent, generation eventGeneration: Int) {
        // Events from a socket that was replaced or detached must not reach JS.
        guard eventGeneration == generation else { return }

        switch event {
        case .connected(let headers):
            connected = true
            closedByClient = false
            timeout?.cancel()
            timeout = nil
            emitState("OPEN")
            notify("connected", ["headers": headers.mapValues { [$0] }])
            settlePendingConnect(error: nil)
        case .disconnected(let reason, let code):
            finishClosed(frame: "\(code) \(reason)")
        case .cancelled:
            finishClosed(frame: "")
        case .text(let string):
            notify("message", ["data": string])
        case .binary(let data):
            // Binary frames are delivered base64 encoded, like on Android and web.
            notify("message", ["data": data.base64EncodedString(), "binary": true])
        case .error(let error):
            let description = SocketConnection.describe(error)
            if connected {
                notify("error", ["cause": description])
            } else {
                notify("connecterror", ["exception": description])
                settlePendingConnect(error: "Couldnt connect socket: \(description)")
            }
            finishClosed(frame: "")
        case .ping, .pong, .viabilityChanged, .reconnectSuggested, .peerClosed:
            break
        }
    }

    /// Starscream's HTTPUpgradeError hides the HTTP status in its localized description; include it so a rejected handshake is diagnosable.
    static func describe(_ error: Error?) -> String {
        guard let error = error else { return "Unknown Error" }
        if let upgrade = error as? HTTPUpgradeError {
            switch upgrade {
            case .notAnUpgrade(let status, let headers):
                return "Handshake rejected by server: HTTP \(status), headers \(headers)"
            case .invalidData:
                return "Handshake failed: invalid response data"
            }
        }
        return error.localizedDescription
    }

    private func finishClosed(frame: String) {
        let wasConnected = connected
        let byClient = closedByClient
        let byServer = wasConnected && !byClient
        let trimmed = frame.trimmingCharacters(in: .whitespaces)

        generation += 1
        timeout?.cancel()
        timeout = nil
        connected = false
        closedByClient = false
        socket?.onEvent = nil
        socket = nil

        if byServer && !trimmed.isEmpty {
            notify("closeframe", ["frame": trimmed])
        }
        emitState("CLOSED")
        notify("disconnected", [
            "closedByServer": byServer,
            "serverCloseFrame": byServer ? trimmed : "",
            "clientCloseFrame": (wasConnected && byClient) ? trimmed : ""
        ])
        settlePendingConnect(error: "Connection closed before it was established")
    }

    private func connectTimedOut(generation attempt: Int) {
        guard attempt == generation, pendingConnect != nil else { return }
        detach()
        notify("connecterror", ["exception": "Socket timeout (5s)"])
        emitState("CLOSED")
        settlePendingConnect(error: "Socket timeout (5s)")
    }

    private func settlePendingConnect(error: String?) {
        guard let call = pendingConnect else { return }
        pendingConnect = nil
        if let error = error {
            call.reject(error)
        } else {
            call.resolve()
        }
    }

    private func emitState(_ state: String) {
        notify("statechange", ["state": state])
    }

    private func notify(_ event: String, _ data: [String: Any]) {
        emit("\(name):\(event)", data)
    }
}

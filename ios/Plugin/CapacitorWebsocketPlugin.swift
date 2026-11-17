import Foundation
import Capacitor
import Starscream

@objc(CapacitorWebsocketPlugin)
public class CapacitorWebsocketPlugin: CAPPlugin {
    var connections: [String: SocketConnection] = [:]

    deinit {
        connections.values.forEach { $0.dispose() }
    }

    @objc public func build(_ call: CAPPluginCall) {
        guard let url = call.getString("url") else {
            call.reject("Must provide a socket URL")
            return
        }

        guard let name = call.getString("name") else {
            call.reject("Must provide a socket name")
            return
        }

        guard let socketURL = URL(string: url) else {
            call.reject("Couldnt make socket: url parse error")
            return
        }

        var req = URLRequest(url: socketURL)

        if let headers = call.getObject("headers") {
            for (key, value) in headers {
                guard let headerValue = value as? String else {
                    call.reject("Header '\(key)' must be a string")
                    return
                }
                req.addValue(headerValue, forHTTPHeaderField: key)
            }
        }

        connections[name]?.dispose()
        connections[name] = SocketConnection(name: name, plugin: self, request: req)
        call.resolve()
    }

    @objc public func applyListeners(_ call: CAPPluginCall) {
        guard let connection = getSocket(call) else { return }
        connection.bind()
        call.resolve()
    }

    @objc public func connect(_ call: CAPPluginCall) {
        guard let connection = getSocket(call) else { return }
        // Resolved once the socket is actually open, rejected if the attempt fails or times out.
        connection.connect(call)
    }

    @objc public func disconnect(_ call: CAPPluginCall) {
        guard let connection = getSocket(call) else { return }
        connection.disconnect(call)
    }

    @objc public func send(_ call: CAPPluginCall) {
        guard let connection = getSocket(call) else { return }
        guard let message = call.getString("data") else {
            call.reject("Must provide data to send")
            return
        }
        guard connection.connected, let socket = connection.socket else {
            call.reject("Socket '\(connection.name)' is not connected")
            return
        }
        socket.write(string: message) {
            call.resolve()
        }
    }

    private func getSocket(_ call: CAPPluginCall) -> SocketConnection? {
        guard let name = call.getString("name") else {
            call.reject("Must provide a socket name")
            return nil
        }

        guard let connection = connections[name] else {
            call.reject("Socket '\(name)' doesnt exist")
            return nil
        }
        return connection
    }
}

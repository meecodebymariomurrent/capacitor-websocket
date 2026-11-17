import XCTest
import Capacitor
import Starscream
@testable import Plugin

/// Controllable stand-in for Starscream's WebSocket.
final class MockTransport: SocketTransport {
    var onEvent: ((WebSocketEvent) -> Void)?
    private(set) var connectCalls = 0
    private(set) var disconnectCodes: [UInt16] = []
    private(set) var forceDisconnected = false
    private(set) var written: [String] = []

    func connect() { connectCalls += 1 }
    func disconnect(closeCode: UInt16) { disconnectCodes.append(closeCode) }
    func forceDisconnect() { forceDisconnected = true }
    func write(string: String, completion: (() -> Void)?) {
        written.append(string)
        completion?()
    }
}

/// Records how a CAPPluginCall was settled.
final class CallRecorder {
    var resolved = 0
    var rejections: [String] = []

    func makeCall(_ method: String = "connect") -> CAPPluginCall {
        return CAPPluginCall(
            callbackId: UUID().uuidString,
            methodName: method,
            options: [:],
            success: { [weak self] _, _ in self?.resolved += 1 },
            error: { [weak self] error in self?.rejections.append(error?.message ?? "") }
        )
    }
}

class CapacitorWebsocketTests: XCTestCase {
    private var plugin: CapacitorWebsocketPlugin!
    private var connection: SocketConnection!
    private var transports: [MockTransport] = []
    private var events: [(name: String, data: [String: Any])] = []
    private var recorder: CallRecorder!

    override func setUp() {
        super.setUp()
        plugin = CapacitorWebsocketPlugin()
        transports = []
        events = []
        recorder = CallRecorder()
        connection = SocketConnection(name: "s", plugin: plugin, request: URLRequest(url: URL(string: "ws://localhost:1")!))
        connection.emit = { [weak self] name, data in self?.events.append((name, data)) }
        connection.makeTransport = { [weak self] _ in
            let transport = MockTransport()
            self?.transports.append(transport)
            return transport
        }
    }

    private var lastTransport: MockTransport { transports[transports.count - 1] }
    private func names() -> [String] { events.map { $0.name } }
    private func states() -> [String] {
        events.filter { $0.name == "s:statechange" }.compactMap { $0.data["state"] as? String }
    }

    private func openConnection() {
        connection.connect(recorder.makeCall())
        lastTransport.onEvent?(.connected([:]))
    }

    func testPluginStartsWithoutConnections() {
        XCTAssertTrue(plugin.connections.isEmpty)
    }

    func testConnectResolvesOnlyOnceTheSocketIsOpen() {
        connection.connect(recorder.makeCall())
        XCTAssertEqual(recorder.resolved, 0)
        XCTAssertEqual(lastTransport.connectCalls, 1)

        lastTransport.onEvent?(.connected(["Server": "x"]))
        XCTAssertEqual(recorder.resolved, 1)
        XCTAssertTrue(connection.connected)

        let connected = events.first { $0.name == "s:connected" }
        let headers = connected?.data["headers"] as? [String: [String]]
        XCTAssertEqual(headers?["Server"], ["x"])
    }

    func testStateChangesFollowConnectingOpenClosingClosed() {
        openConnection()
        connection.disconnect(recorder.makeCall("disconnect"))
        lastTransport.onEvent?(.disconnected("", 1000))
        XCTAssertEqual(states(), ["CONNECTING", "OPEN", "CLOSING", "CLOSED"])
    }

    func testFailedConnectEmitsConnectErrorAndRejects() {
        connection.connect(recorder.makeCall())
        lastTransport.onEvent?(.error(nil))
        XCTAssertEqual(recorder.rejections.count, 1)
        XCTAssertTrue(names().contains("s:connecterror"))
        XCTAssertFalse(names().contains("s:error"))
        XCTAssertFalse(connection.connected)
    }

    func testTextAndBinaryMessages() {
        openConnection()
        lastTransport.onEvent?(.text("hi"))
        lastTransport.onEvent?(.binary(Data([1, 2, 3])))
        let messages = events.filter { $0.name == "s:message" }
        XCTAssertEqual(messages[0].data["data"] as? String, "hi")
        XCTAssertNil(messages[0].data["binary"])
        XCTAssertEqual(messages[1].data["data"] as? String, "AQID")
        XCTAssertEqual(messages[1].data["binary"] as? Bool, true)
    }

    func testServerCloseEmitsCloseFrameThenDisconnected() {
        openConnection()
        lastTransport.onEvent?(.disconnected("bye", 1001))
        let relevant = names().filter { $0 == "s:closeframe" || $0 == "s:disconnected" }
        XCTAssertEqual(relevant, ["s:closeframe", "s:disconnected"])
        let disconnected = events.first { $0.name == "s:disconnected" }
        XCTAssertEqual(disconnected?.data["closedByServer"] as? Bool, true)
        XCTAssertEqual(disconnected?.data["serverCloseFrame"] as? String, "1001 bye")
        XCTAssertEqual(disconnected?.data["clientCloseFrame"] as? String, "")
    }

    func testClientDisconnectReportsClientCloseFrame() {
        openConnection()
        connection.disconnect(recorder.makeCall("disconnect"))
        XCTAssertEqual(lastTransport.disconnectCodes, [1000])
        lastTransport.onEvent?(.disconnected("", 1000))
        let disconnected = events.first { $0.name == "s:disconnected" }
        XCTAssertEqual(disconnected?.data["closedByServer"] as? Bool, false)
        XCTAssertEqual(disconnected?.data["serverCloseFrame"] as? String, "")
        XCTAssertEqual(disconnected?.data["clientCloseFrame"] as? String, "1000")
    }

    func testCancelledEventStillEmitsDisconnected() {
        openConnection()
        lastTransport.onEvent?(.cancelled)
        XCTAssertTrue(names().contains("s:disconnected"))
        XCTAssertFalse(connection.connected)
    }

    func testPeerClosedIsNotReportedAsAMessage() {
        openConnection()
        lastTransport.onEvent?(.peerClosed)
        XCTAssertFalse(names().contains("s:message"))
    }

    func testReconnectUsesAFreshTransport() {
        openConnection()
        lastTransport.onEvent?(.disconnected("bye", 1001))
        let second = CallRecorder()
        connection.connect(second.makeCall())
        XCTAssertEqual(transports.count, 2)
        lastTransport.onEvent?(.connected([:]))
        XCTAssertEqual(second.resolved, 1)
    }

    func testConnectWhileConnectedResolvesWithoutNewTransport() {
        openConnection()
        let again = CallRecorder()
        connection.connect(again.makeCall())
        XCTAssertEqual(again.resolved, 1)
        XCTAssertEqual(transports.count, 1)
    }

    func testSecondConnectWhileConnectingIsRejected() {
        connection.connect(recorder.makeCall())
        let second = CallRecorder()
        connection.connect(second.makeCall())
        XCTAssertEqual(second.rejections.count, 1)
        XCTAssertEqual(transports.count, 1)
    }

    func testDisconnectWhileConnectingRejectsThePendingConnect() {
        connection.connect(recorder.makeCall())
        connection.disconnect(recorder.makeCall("disconnect"))
        XCTAssertEqual(recorder.rejections.count, 1)
        XCTAssertTrue(lastTransport.forceDisconnected)
        XCTAssertFalse(connection.connected)
    }

    func testEventsFromAReplacedSocketAreIgnored() {
        openConnection()
        let first = lastTransport
        connection.dispose()
        events.removeAll()
        first.onEvent?(.text("stale"))
        XCTAssertTrue(events.isEmpty)
    }

    func testConnectTimesOut() {
        connection.connectTimeout = 0.05
        connection.connect(recorder.makeCall())
        let expectation = XCTestExpectation(description: "timeout")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { expectation.fulfill() }
        wait(for: [expectation], timeout: 2)
        XCTAssertEqual(recorder.rejections.count, 1)
        XCTAssertTrue(names().contains("s:connecterror"))
    }
}

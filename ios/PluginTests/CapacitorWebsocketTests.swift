import XCTest
@testable import Plugin

class CapacitorWebsocketTests: XCTestCase {
    func testPluginStartsWithoutConnections() {
        let plugin = CapacitorWebsocketPlugin()
        XCTAssertTrue(plugin.connections.isEmpty)
    }
}

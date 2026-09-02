import XCTest
@testable import EzClipboardBridge

final class MathParserTests: XCTestCase {
    func testCommonExpressions() throws {
        XCTAssertEqual(try parse("3*4+2"), 14)
        XCTAssertEqual(try parse("(8 + 4) / 3"), 4)
        XCTAssertEqual(try parse("2^3^2"), 512)
        XCTAssertEqual(try parse("-2.5 * 4"), -10)
    }

    func testInvalidExpressions() {
        XCTAssertThrowsError(try parse("1/0"))
        XCTAssertThrowsError(try parse("2+"))
        XCTAssertThrowsError(try parse("hello"))
    }

    private func parse(_ text: String) throws -> Double {
        var parser = MathParser(text)
        return try parser.parse()
    }
}

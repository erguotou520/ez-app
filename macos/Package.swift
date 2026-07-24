// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "EzClipboardBridge",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "ez-clipboard-bridge", targets: ["EzClipboardBridge"]),
    ],
    targets: [
        .executableTarget(name: "EzClipboardBridge"),
    ],
    swiftLanguageVersions: [.v5]
)

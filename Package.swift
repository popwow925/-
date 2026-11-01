// swift-tools-version: 5.9
import PackageDescription

// FrenemyCore: pure Swift (Foundation only). No UIKit, no MultipeerConnectivity.
// FrenemyDemo: offline demo runner. Depends on FrenemyCore ONLY (never on the App's Networking code).
let package = Package(
    name: "FrenemyIsle",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "FrenemyCore", targets: ["FrenemyCore"]),
        .library(name: "FrenemyDemo", targets: ["FrenemyDemo"]),
    ],
    targets: [
        .target(name: "FrenemyCore"),
        .target(name: "FrenemyDemo", dependencies: ["FrenemyCore"]),
        .testTarget(name: "FrenemyCoreTests", dependencies: ["FrenemyCore", "FrenemyDemo"]),
    ]
)

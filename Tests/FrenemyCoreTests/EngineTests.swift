import XCTest
@testable import FrenemyCore
import FrenemyDemo

final class EngineFlowTests: XCTestCase {
    private func makeEngine(players: Int = 3, config: GameConfig = GameConfig()) -> GameEngine {
        let e = GameEngine(config: config, roomCode: "T", library: nil,
                           random: SeededRandom(seed: 5), implementedGames: DemoRoom.defaultImplemented)
        for i in 1...players {
            XCTAssertNil(e.join(Player(id: "p\(i)", nickname: "P\(i)"), protocolVersion: WireProtocol.version, contentVersion: 0))
        }
        return e
    }

    func testStartNeedsTwoPlayersAndValidConfig() {
        let one = makeEngine(players: 1)
        XCTAssertThrowsError(try one.start())
        var bad = GameConfig()
        bad.totalRounds = 9
        let e = makeEngine()
        XCTAssertThrowsError(try e.updateConfig(bad))
    }

    func testHandshakeMismatchIsRejected() {
        let e = makeEngine(players: 2)
        XCTAssertEqual(e.join(Player(id: "x", nickname: "X"), protocolVersion: 99, contentVersion: 0), .protocolMismatch)
        XCTAssertEqual(e.join(Player(id: "y", nickname: "Y"), protocolVersion: WireProtocol.version, contentVersion: 7), .contentPackMismatch)
    }

    func testRoomFullAndLateJoinRejectedButReconnectAllowed() throws {
        let e = makeEngine(players: Limits.maxPlayers)
        XCTAssertEqual(e.join(Player(id: "extra", nickname: "E"), protocolVersion: WireProtocol.version, contentVersion: 0), .roomFull)
        try e.start()
        XCTAssertEqual(e.join(Player(id: "late", nickname: "L"), protocolVersion: WireProtocol.version, contentVersion: 0), .gameInProgress)
        e.markConnected("p1", false)
        XCTAssertNil(e.join(Player(id: "p1", nickname: "P1"), protocolVersion: WireProtocol.version, contentVersion: 0))
        XCTAssertTrue(e.publicState().players.first(where: { $0.id == "p1" })?.isConnected ?? false)
    }

    func testKickedPlayerCannotRejoin() {
        let e = makeEngine(players: 3)
        e.kick("p2")
        XCTAssertEqual(e.join(Player(id: "p2", nickname: "P2"), protocolVersion: WireProtocol.version, contentVersion: 0), .kicked)
    }

    func testMiniGameWaitsForEveryConnectedPlayer() throws {
        let e = makeEngine(players: 3)
        try e.start()
        while e.phase != .miniGame { XCTAssertTrue(e.step()) }
        XCTAssertFalse(e.step())                       // nobody submitted
        e.submitScore("p1", 10)
        e.submitScore("p2", 20)
        XCTAssertFalse(e.step())                       // p3 missing
        e.markConnected("p3", false)                   // a disconnected player no longer blocks the game
        XCTAssertTrue(e.step())
        XCTAssertEqual(e.phase, .payout)
    }

    func testStatueRanksLowerJitterBetter() throws {
        // Unit-level check of the direction flag used by resolveMiniGame.
        XCTAssertTrue(MiniGameID.statue.lowerIsBetter)
        XCTAssertFalse(MiniGameID.shakeFest.lowerIsBetter)
        XCTAssertTrue(MiniGameID.shakeFest.allowedAsFinale)
        XCTAssertFalse(MiniGameID.undercover.allowedAsFinale)
    }

    func testBoardRingWrapsAndReversesAndLayoutIsComplete() {
        XCTAssertEqual(Board.layout.count, Board.size)
        XCTAssertEqual(Board.advance(from: 22, steps: 5, reversed: false), 3)
        XCTAssertEqual(Board.advance(from: 2, steps: 5, reversed: true), 21)
        for kind in TileKind.allCases { XCTAssertTrue(Board.layout.contains(kind), "missing \(kind)") }
    }
}

final class OfflineDemoTests: XCTestCase {
    func testAutoplayFinishesAndFollowsLastRoundRules() throws {
        let room = DemoRoom(seed: 2026, botCount: 3)
        try room.startGame()
        let final = room.autoplay()
        XCTAssertEqual(room.engine.phase, .ended)
        XCTAssertEqual(final.players.count, 4)
        XCTAssertNotNil(final.standings)
        XCTAssertNotNil(final.revealedMissions)

        let total = room.engine.totalRounds
        XCTAssertTrue(room.log.contains("r\(total):betting"))
        XCTAssertTrue(room.log.contains("r\(total):finale"))
        XCTAssertTrue(room.log.contains("r\(total):results"))
        XCTAssertFalse(room.log.contains("r\(total):sabotage"), "last round must not allow sabotage cards")
        XCTAssertFalse(room.log.contains("r\(total):award"), "no award in the last round")
        XCTAssertFalse(room.log.contains("r\(total):roll"), "last round skips board movement")
        XCTAssertTrue(room.log.contains("r3:award"))
        XCTAssertTrue(room.log.contains("r6:award"))
        XCTAssertFalse(room.log.contains("r4:award"))
    }

    func testAutoplayIsDeterministicForTheSameSeed() throws {
        let a = DemoRoom(seed: 11, botCount: 3)
        try a.startGame()
        let sa = a.autoplay()
        let b = DemoRoom(seed: 11, botCount: 3)
        try b.startGame()
        let sb = b.autoplay()
        XCTAssertEqual(sa.standings, sb.standings)
        XCTAssertEqual(a.log, b.log)
    }

    func testPublicStateNeverCarriesPrivateFieldsBeforeTheGameEnds() throws {
        let room = DemoRoom(seed: 3, botCount: 3)
        var jsons: [String] = []
        var privacyViolations = 0
        room.human?.onPublicState = { s in
            if let data = try? Codec.encode(s), let text = String(data: data, encoding: .utf8) {
                jsons.append(text)
                for banned in ["\"vouchers\"", "\"awardPrompt\"", "\"word\"", "\"bet\""] where text.contains(banned) {
                    privacyViolations += 1
                }
                // Secret missions are public only after the game has ended.
                if s.phase != .ended {
                    for banned in ["\"missionID\"", "\"holder\"", "\"revealedMissions\":["] where text.contains(banned) {
                        privacyViolations += 1
                    }
                }
            }
        }
        var wrongRecipient = 0
        room.human?.onPrivateState = { p in if p.player != "human" { wrongRecipient += 1 } }
        try room.startGame()
        _ = room.autoplay()
        XCTAssertFalse(jsons.isEmpty)
        XCTAssertEqual(privacyViolations, 0)
        XCTAssertEqual(wrongRecipient, 0)
    }

    func testReconnectWindowIsEnforcedByHostSession() throws {
        var now: Int64 = 1_000
        let hub = LoopbackHub()
        let engine = GameEngine(roomCode: "R", library: nil, random: SeededRandom(seed: 1),
                                implementedGames: DemoRoom.defaultImplemented)
        let host = HostSession(engine: engine, transport: hub.makeHostTransport(), clock: { now })
        host.start()
        let p = Player(id: "p1", nickname: "P1")
        let c = ClientSession(player: p, transport: hub.makeClientTransport(id: "p1"), contentVersion: 0, uiLocale: "en", clock: { now })
        c.start()
        XCTAssertEqual(engine.playerIDs, ["p1"])
        c.stop()                                        // connection drops
        XCTAssertEqual(engine.connectedIDs, [])
        now += Int64((Limits.reconnectWindowSeconds + 1) * 1000)
        let late = ClientSession(player: p, transport: hub.makeClientTransport(id: "p1"), contentVersion: 0, uiLocale: "en", clock: { now })
        late.start()
        XCTAssertEqual(late.rejectReason, .gameInProgress)
    }

    func testLatencyIsMeasuredAtHandshake() {
        let room = DemoRoom(seed: 4, botCount: 2)
        XCTAssertEqual(room.host.latency(of: "bot1").samples, HostSession.pingsPerPeer)
    }

    func testHandshakeContentMismatchIsRejectedOverTheTransport() {
        let hub = LoopbackHub()
        let engine = GameEngine(roomCode: "M", library: nil, random: SeededRandom(seed: 1),
                                implementedGames: DemoRoom.defaultImplemented)
        let host = HostSession(engine: engine, transport: hub.makeHostTransport())
        host.start()
        let c = ClientSession(player: Player(id: "z", nickname: "Z"), transport: hub.makeClientTransport(id: "z"),
                              contentVersion: 9, uiLocale: "en")
        c.start()
        XCTAssertEqual(c.rejectReason, .contentPackMismatch)
        XCTAssertTrue(engine.playerIDs.isEmpty)
    }
}

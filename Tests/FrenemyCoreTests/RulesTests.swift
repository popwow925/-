import XCTest
@testable import FrenemyCore

final class SabotageTests: XCTestCase {
    private func ranked(_ pairs: [(String, Int)]) -> [RankedResult] {
        pairs.map { RankedResult(player: $0.0, rank: $0.1) }
    }

    func testVoucherCountIsCeilThirdWithMinimumOne() {
        XCTAssertEqual(SabotageRules.voucherCount(playerCount: 2), 1)
        XCTAssertEqual(SabotageRules.voucherCount(playerCount: 3), 1)
        XCTAssertEqual(SabotageRules.voucherCount(playerCount: 4), 2)
        XCTAssertEqual(SabotageRules.voucherCount(playerCount: 6), 2)
        XCTAssertEqual(SabotageRules.voucherCount(playerCount: 7), 3)
        XCTAssertEqual(SabotageRules.voucherCount(playerCount: 8), 3)
    }

    func testNoVouchersInLastTwoRounds() {
        XCTAssertTrue(SabotageRules.issuesVouchers(afterRound: 6, totalRounds: 8))
        XCTAssertFalse(SabotageRules.issuesVouchers(afterRound: 7, totalRounds: 8))
        XCTAssertFalse(SabotageRules.issuesVouchers(afterRound: 8, totalRounds: 8))
    }

    func testRecipientsAreWorstThirdAndTiesAtBoundaryAllReceive() {
        let six = ranked([("a", 1), ("b", 2), ("c", 3), ("d", 4), ("e", 5), ("f", 6)])
        XCTAssertEqual(Set(SabotageRules.voucherRecipients(results: six, round: 1, totalRounds: 8)), ["e", "f"])
        let tied = ranked([("a", 1), ("b", 2), ("c", 3), ("d", 4), ("e", 4), ("f", 6)])
        XCTAssertEqual(Set(SabotageRules.voucherRecipients(results: tied, round: 1, totalRounds: 8)), ["d", "e", "f"])
        XCTAssertTrue(SabotageRules.voucherRecipients(results: six, round: 7, totalRounds: 8).isEmpty)
    }

    func testVoucherUsabilityWindow() {
        let v = Voucher(id: 1, owner: "a", issuedRound: 2)
        XCTAssertEqual(v.usability(inRound: 2, totalRounds: 8), .notYet)
        XCTAssertEqual(v.usability(inRound: 3, totalRounds: 8), .usable)
        XCTAssertEqual(v.usability(inRound: 4, totalRounds: 8), .usable)
        XCTAssertEqual(v.usability(inRound: 5, totalRounds: 8), .expired)
        XCTAssertEqual(v.usability(inRound: 8, totalRounds: 8), .lastRoundDisabled)
    }

    func testVoucherIssuedThisRoundCannotBeUsedThisRound() {
        var l = SabotageLedger(players: ["a", "b", "c"], strongEnabled: false)
        l.issueVouchers(results: ranked([("a", 1), ("b", 2), ("c", 3)]), round: 1, totalRounds: 8)
        XCTAssertEqual(l.play(card: .reverseDice, by: "c", on: "a", round: 1, totalRounds: 8, strongEnabled: false),
                       .rejected(.notYetUsable))
        XCTAssertEqual(l.play(card: .reverseDice, by: "c", on: "a", round: 2, totalRounds: 8, strongEnabled: false),
                       .applied)
    }

    func testOneCardPerRoundAndNoSelfTarget() {
        var l = SabotageLedger(players: ["a", "b", "c"], strongEnabled: false)
        let r = ranked([("a", 1), ("b", 2), ("c", 3)])
        l.issueVouchers(results: r, round: 1, totalRounds: 8)
        l.endRound(1)
        l.issueVouchers(results: r, round: 2, totalRounds: 8)
        l.endRound(2)
        XCTAssertEqual(l.play(card: .reverseDice, by: "c", on: "c", round: 3, totalRounds: 8, strongEnabled: false),
                       .rejected(.selfTarget))
        XCTAssertEqual(l.play(card: .reverseDice, by: "c", on: "a", round: 3, totalRounds: 8, strongEnabled: false), .applied)
        XCTAssertEqual(l.play(card: .leftHandRule, by: "c", on: "b", round: 3, totalRounds: 8, strongEnabled: false),
                       .rejected(.alreadyUsedThisRound))
    }

    func testStrongCardsDisabledByDefaultAndBlockedByShield() {
        var off = SabotageLedger(players: ["a", "b"], strongEnabled: false)
        off.issueVouchers(results: ranked([("a", 1), ("b", 2)]), round: 1, totalRounds: 8)
        XCTAssertEqual(off.play(card: .stealCrystals, by: "b", on: "a", round: 2, totalRounds: 8, strongEnabled: false),
                       .rejected(.strongDisabled))

        var on = SabotageLedger(players: ["a", "b"], strongEnabled: true)
        XCTAssertEqual(on.shieldCount("a"), 1)                       // initial shield when strong is enabled
        on.issueVouchers(results: ranked([("a", 1), ("b", 2)]), round: 1, totalRounds: 8)
        XCTAssertEqual(on.play(card: .stealCrystals, by: "b", on: "a", round: 2, totalRounds: 8, strongEnabled: true),
                       .blockedByShield)
        XCTAssertEqual(on.shieldCount("a"), 0)
        XCTAssertEqual(on.stats.shieldUses, 1)
    }

    func testSameTargetHitByOnlyOneStrongCardPerRound() {
        var l = SabotageLedger(players: ["a", "b", "c"], strongEnabled: true)
        let r = ranked([("a", 1), ("b", 2), ("c", 3)])
        l.issueVouchers(results: [RankedResult(player: "b", rank: 3), RankedResult(player: "c", rank: 3), RankedResult(player: "a", rank: 1)],
                        round: 1, totalRounds: 8)
        _ = r
        XCTAssertEqual(l.play(card: .stealCrystals, by: "b", on: "a", round: 2, totalRounds: 8, strongEnabled: true), .blockedByShield)
        XCTAssertEqual(l.play(card: .skipShop, by: "c", on: "a", round: 2, totalRounds: 8, strongEnabled: true),
                       .rejected(.targetAlreadyHitThisRound))
    }

    func testLastRoundDisablesCards() {
        var l = SabotageLedger(players: ["a", "b"], strongEnabled: false)
        l.issueVouchers(results: ranked([("a", 1), ("b", 2)]), round: 1, totalRounds: 8)
        XCTAssertEqual(l.play(card: .reverseDice, by: "b", on: "a", round: 8, totalRounds: 8, strongEnabled: false),
                       .rejected(.lastRoundDisabled))
    }

    func testVouchersExpireAfterSecondFollowingRound() {
        var l = SabotageLedger(players: ["a", "b"], strongEnabled: false)
        l.issueVouchers(results: ranked([("a", 1), ("b", 2)]), round: 1, totalRounds: 8)
        XCTAssertEqual(l.vouchers.count, 1)
        l.endRound(1)
        l.endRound(2)
        XCTAssertEqual(l.vouchers.count, 1)          // still valid through round 3
        l.endRound(3)
        XCTAssertEqual(l.vouchers.count, 0)
    }

    func testShieldCapAndLastPlaceBonus() {
        var l = SabotageLedger(players: ["a", "b"], strongEnabled: true)
        XCTAssertTrue(l.addShield("a"))             // 1 -> 2
        XCTAssertFalse(l.addShield("a"))            // cap 2
        l.grantLastPlaceShields(results: ranked([("a", 1), ("b", 2)]))
        XCTAssertEqual(l.shieldCount("b"), 2)
    }

    func testStatsAreNeutralAndHaveNoReceivedCount() {
        var l = SabotageLedger(players: ["a", "b"], strongEnabled: false)
        l.issueVouchers(results: ranked([("a", 1), ("b", 2)]), round: 1, totalRounds: 8)
        _ = l.play(card: .leftHandRule, by: "b", on: "a", round: 2, totalRounds: 8, strongEnabled: false)
        XCTAssertEqual(l.stats.totalCardsPlayed, 1)
        XCTAssertEqual(l.stats.mostUsedCard, .leftHandRule)
    }
}

final class FinalBetTests: XCTestCase {
    func testMaxStakeLinearByRank() {
        XCTAssertEqual(FinalBetRules.maxStake(crystals: 100, rank: 1, playerCount: 4), 20)
        XCTAssertEqual(FinalBetRules.maxStake(crystals: 100, rank: 2, playerCount: 4), 30)
        XCTAssertEqual(FinalBetRules.maxStake(crystals: 100, rank: 3, playerCount: 4), 40)
        XCTAssertEqual(FinalBetRules.maxStake(crystals: 100, rank: 4, playerCount: 4), 50)
        XCTAssertEqual(FinalBetRules.maxStake(crystals: 100, rank: 1, playerCount: 2), 20)
        XCTAssertEqual(FinalBetRules.maxStake(crystals: 100, rank: 2, playerCount: 2), 50)
        XCTAssertEqual(FinalBetRules.maxStake(crystals: 0, rank: 1, playerCount: 4), 0)
    }

    private func setup() -> (FinalBetBook, [PlayerID: Int], [RankedResult]) {
        let crystals = ["a": 50, "b": 30, "c": 10]
        return (FinalBetBook(players: ["a", "b", "c"]), crystals, Ranking.compete(scores: crystals))
    }

    /// `Result<Void, _>` is not Equatable, so tests compare the failure case only.
    private func failure(_ r: Result<Void, BetError>) -> BetError? {
        if case .failure(let e) = r { return e }
        return nil
    }

    func testValidationErrors() {
        var (book, crystals, ranks) = setup()
        XCTAssertEqual(failure(book.place(Bet(bettor: "a", target: "a", stake: 5), crystals: &crystals, ranks: ranks)), .selfBet)
        XCTAssertEqual(failure(book.place(Bet(bettor: "a", target: "b", stake: 0), crystals: &crystals, ranks: ranks)), .stakeNotPositive)
        XCTAssertEqual(failure(book.place(Bet(bettor: "a", target: "b", stake: 11), crystals: &crystals, ranks: ranks)), .stakeAboveCap)
        XCTAssertEqual(failure(book.place(Bet(bettor: "a", target: "zzz", stake: 5), crystals: &crystals, ranks: ranks)), .unknownTarget)
        XCTAssertNil(failure(book.place(Bet(bettor: "a", target: "b", stake: 10), crystals: &crystals, ranks: ranks)))
        XCTAssertEqual(failure(book.place(Bet(bettor: "a", target: "c", stake: 1), crystals: &crystals, ranks: ranks)), .alreadyBet)
        ranks = []   // keeps `ranks` mutable-use warning-free
    }

    func testStakeIsFrozenThenDoubledWhenCorrect() {
        var (book, crystals, ranks) = setup()
        _ = book.place(Bet(bettor: "a", target: "b", stake: 10), crystals: &crystals, ranks: ranks)
        XCTAssertEqual(crystals["a"], 40)
        let outcomes = book.settle(winners: ["b"], crystals: &crystals)
        XCTAssertEqual(crystals["a"], 60)            // 40 + 2 x 10: principal back plus equal profit
        XCTAssertEqual(outcomes.first?.won, true)
    }

    func testWrongBetIsVoidedNotTransferred() {
        var (book, crystals, ranks) = setup()
        _ = book.place(Bet(bettor: "a", target: "b", stake: 10), crystals: &crystals, ranks: ranks)
        let total = crystals.values.reduce(0, +)
        _ = book.settle(winners: ["c"], crystals: &crystals)
        XCTAssertEqual(crystals["a"], 40)
        XCTAssertEqual(crystals.values.reduce(0, +), total)   // nobody received the lost stake
    }

    func testTiedWinnersBothCountAsCorrect() {
        var (book, crystals, ranks) = setup()
        _ = book.place(Bet(bettor: "a", target: "c", stake: 10), crystals: &crystals, ranks: ranks)
        let outcomes = book.settle(winners: ["b", "c"], crystals: &crystals)
        XCTAssertEqual(outcomes.first?.won, true)
    }
}

final class RankingAndLatencyTests: XCTestCase {
    func testCompetitionRanking() {
        let r = Ranking.compete(scores: ["a": 10, "b": 10, "c": 5])
        XCTAssertEqual(r.first(where: { $0.player == "a" })?.rank, 1)
        XCTAssertEqual(r.first(where: { $0.player == "b" })?.rank, 1)
        XCTAssertEqual(r.first(where: { $0.player == "c" })?.rank, 3)
        XCTAssertEqual(Ranking.champions(r), ["a", "b"])
    }

    func testMedianRttHalved() {
        XCTAssertEqual(LatencyEstimator.estimate(rttSamplesMs: [60, 20, 40]).oneWayMs, 20)
        XCTAssertEqual(LatencyEstimator.estimate(rttSamplesMs: [20, 40]).oneWayMs, 15)
        XCTAssertEqual(LatencyEstimator.estimate(rttSamplesMs: []).samples, 0)
    }

    func testResultsWithinThresholdTie() {
        let subs = [
            ReactionSubmission(player: "a", reactionMs: 200, oneWayMs: 0),
            ReactionSubmission(player: "b", reactionMs: 230, oneWayMs: 0),
            ReactionSubmission(player: "c", reactionMs: 300, oneWayMs: 0),
        ]
        let ranks = ReactionJudge.rank(subs)
        XCTAssertEqual(ranks.first(where: { $0.player == "a" })?.rank, 1)
        XCTAssertEqual(ranks.first(where: { $0.player == "b" })?.rank, 1)
        XCTAssertEqual(ranks.first(where: { $0.player == "c" })?.rank, 3)
    }

    func testThresholdIsConfigurableAndEarlyTapsRankLast() {
        let subs = [
            ReactionSubmission(player: "a", reactionMs: 200, oneWayMs: 0),
            ReactionSubmission(player: "b", reactionMs: 230, oneWayMs: 0),
            ReactionSubmission(player: "x", reactionMs: -1, oneWayMs: 0),
        ]
        let strict = ReactionJudge.rank(subs, thresholdMs: 10)
        XCTAssertEqual(strict.first(where: { $0.player == "b" })?.rank, 2)
        XCTAssertEqual(strict.first(where: { $0.player == "x" })?.rank, 3)
    }

    func testDelayCorrectionAddsOneWayDelay() {
        XCTAssertEqual(ReactionJudge.correctedMs(ReactionSubmission(player: "a", reactionMs: 200, oneWayMs: 15)), 215)
    }
}

final class AwardTests: XCTestCase {
    func testScheduleSkipsLastRound() {
        XCTAssertEqual(AwardRules.awardRounds(totalRounds: 8), [3, 6])
        XCTAssertEqual(AwardRules.awardRounds(totalRounds: 12), [3, 6, 9])
        XCTAssertEqual(AwardRules.awardRounds(totalRounds: 16), [3, 6, 9, 12, 15])
        XCTAssertFalse(AwardRules.isAwardRound(12, totalRounds: 12))
    }

    private func round() -> AwardRound {
        AwardRound(questionID: "prompt.most_likely.001", titleID: "title.sunshine", players: ["a", "b", "c", "d"])
    }

    func testTieSharesTitleAndVotesAreWiped() {
        var r = round()
        XCTAssertNil(r.vote(voter: "a", target: "c"))
        XCTAssertNil(r.vote(voter: "b", target: "d"))
        XCTAssertEqual(r.votesReceived, 2)
        let winners = r.finalize()
        XCTAssertEqual(winners.map { $0.player }, ["c", "d"])
        XCTAssertEqual(r.votesReceived, 0)
        XCTAssertEqual(r.vote(voter: "a", target: "c"), .finalized)
    }

    func testSelfVoteRejected() {
        var r = round()
        XCTAssertEqual(r.vote(voter: "a", target: "a"), .selfVote)
    }

    func testDeclineLeavesTitleVacantAndNotPassedDown() {
        var r = round()
        _ = r.vote(voter: "a", target: "c")
        _ = r.vote(voter: "b", target: "c")
        _ = r.vote(voter: "c", target: "d")
        r.finalize()
        XCTAssertNil(r.decline("c"))
        XCTAssertTrue(r.reportEntries.isEmpty)          // d did not win, and nothing is passed down
    }

    func testReplaceOnlyFromBuiltInLibraryAndReportOnlyConfirmed() {
        var r = round()
        _ = r.vote(voter: "a", target: "c")
        r.finalize()
        XCTAssertEqual(r.replace("c", with: "title.custom_text"), .titleNotInLibrary)
        XCTAssertTrue(r.reportEntries.isEmpty)           // still pending
        XCTAssertNil(r.replace("c", with: "title.planner"))
        XCTAssertEqual(r.reportEntries.first?.titleID, "title.planner")
        XCTAssertNil(r.setShowInReport("c", false))
        XCTAssertTrue(r.reportEntries.isEmpty)
        XCTAssertEqual(r.accept("zzz"), .notAWinner)
    }
}

final class MissionTests: XCTestCase {
    func testMaxMissions() {
        XCTAssertEqual(MissionRules.maxMissions(totalRounds: 8), 1)
        XCTAssertEqual(MissionRules.maxMissions(totalRounds: 12), 2)
        XCTAssertEqual(MissionRules.maxMissions(totalRounds: 16), 2)
    }

    func testEligibility() {
        XCTAssertTrue(MissionRules.isEligible(round: 2, totalRounds: 8, miniGame: .shakeFest))
        XCTAssertFalse(MissionRules.isEligible(round: 2, totalRounds: 8, miniGame: .undercover))
        XCTAssertFalse(MissionRules.isEligible(round: 2, totalRounds: 8, miniGame: .mostLikely))
        XCTAssertFalse(MissionRules.isEligible(round: 8, totalRounds: 8, miniGame: .shakeFest))
    }

    func testPlanRespectsRulesAcrossSeeds() {
        for seed in 1...50 {
            let rng = SeededRandom(seed: UInt64(seed))
            var games: [MiniGameID] = []
            for i in 0..<16 { games.append(i % 4 == 0 ? .undercover : .shakeFest) }
            let plan = MissionRules.plan(totalRounds: 16, miniGames: games, players: ["a", "b", "c"],
                                         enabled: true, friendlyMode: true, random: rng)
            XCTAssertLessThanOrEqual(plan.count, 2)
            for m in plan {
                XCTAssertTrue(MissionRules.isEligible(round: m.round, totalRounds: 16, miniGame: games[m.round - 1]))
                XCTAssertTrue(MissionPool.available(friendlyMode: true).contains { $0.id == m.missionID })
            }
            if plan.count == 2 { XCTAssertGreaterThanOrEqual(abs(plan[0].round - plan[1].round), 3) }
        }
    }

    func testDisabledGivesNoMissionsAndDisconnectVoidsWithoutPenalty() {
        let rng = SeededRandom(seed: 1)
        XCTAssertTrue(MissionRules.plan(totalRounds: 8, miniGames: Array(repeating: .shakeFest, count: 8),
                                        players: ["a", "b"], enabled: false, friendlyMode: true, random: rng).isEmpty)
        let a = MissionAssignment(round: 2, holder: "a", missionID: "mission.make_table_laugh")
        let r = MissionRules.resolve(a, completed: true, holderConnected: false)
        XCTAssertEqual(r.assignment.outcome, .voided)
        XCTAssertEqual(r.reward, 0)
        XCTAssertEqual(MissionRules.resolve(a, completed: true, holderConnected: true).reward, MissionRules.rewardCrystals)
    }
}

final class ModerationAndContentTests: XCTestCase {
    func testFilterCatchesSeparatorEvasionAndLengthLimit() {
        let f = TextFilter(blockedTerms: ["bad"])
        XCTAssertEqual(f.checkNickname("B.a d"), .blocked)
        XCTAssertEqual(f.checkNickname("   "), .empty)
        XCTAssertEqual(f.checkNickname(String(repeating: "x", count: 13)), .tooLong)
        XCTAssertEqual(f.checkNickname(" Amy "), .ok("Amy"))
    }

    func testReportInboxIsLocalAndWiped() {
        var inbox = ReportInbox()
        XCTAssertNil(inbox.submit(reporter: "a", target: "a", content: .nickname, round: 1))
        XCTAssertNotNil(inbox.submit(reporter: "a", target: "b", content: .drawing, round: 2))
        XCTAssertEqual(inbox.count(against: "b"), 1)
        inbox.wipe()
        XCTAssertTrue(inbox.reports.isEmpty)
    }

    func testLocalHideListIsPerContentKind() {
        var h = LocalHideList()
        h.hide("b", content: .drawing)
        XCTAssertTrue(h.isHidden("b", content: .drawing))
        XCTAssertFalse(h.isHidden("b", content: .title))
        h.wipe()
        XCTAssertFalse(h.isHidden("b", content: .drawing))
    }

    func testHandshakeChecksProtocolAndContentVersion() {
        XCTAssertNil(Handshake.validate(clientProtocol: 1, clientContentVersion: 3, hostProtocol: 1, hostContentVersion: 3))
        XCTAssertEqual(Handshake.validate(clientProtocol: 2, clientContentVersion: 3, hostProtocol: 1, hostContentVersion: 3), .protocolMismatch)
        XCTAssertEqual(Handshake.validate(clientProtocol: 1, clientContentVersion: 2, hostProtocol: 1, hostContentVersion: 3), .contentPackMismatch)
    }

    func testContentLibraryRequiresSameVersionAndFallsBack() throws {
        let zh = ContentPack(version: 1, locale: "zh-Hans", entries: ["title.sunshine": "小太阳"])
        let en = ContentPack(version: 1, locale: "en", entries: ["title.sunshine": "Sunshine"])
        let lib = try ContentLibrary(packs: [zh, en])
        XCTAssertEqual(lib.text("title.sunshine", locale: .en), "Sunshine")
        XCTAssertEqual(lib.text("title.unknown", locale: .en), ContentLibrary.fallbackText["en"])
        XCTAssertFalse(lib.missingLog.isEmpty)
        let bad = ContentPack(version: 2, locale: "en", entries: [:])
        XCTAssertThrowsError(try ContentLibrary(packs: [zh, bad]))
    }

    func testEnvelopeRoundTrip() throws {
        let hello = HelloPayload(playerID: "p1", nickname: "Amy", avatar: Avatar(shape: 1, face: 2, colorIndex: 3), uiLocale: "en")
        let env = Envelope(contentVersion: 1, type: .hello, seq: 1, timestampMs: 5, payload: try Codec.encode(hello))
        let back = try Codec.decode(Envelope.self, from: try Codec.encode(env))
        XCTAssertEqual(back, env)
        XCTAssertEqual(try Codec.decode(HelloPayload.self, from: back.payload), hello)
    }

    func testInputPayloadCodable() throws {
        let inputs: [InputPayload] = [.score(5), .buyShield, .playCard(.reverseDice, target: "b"),
                                      .bet(target: "c", stake: 4), .awardReplace(titleID: "title.planner"),
                                      .report(target: "b", content: .nickname)]
        for i in inputs {
            XCTAssertEqual(try Codec.decode(InputPayload.self, from: try Codec.encode(i)), i)
        }
    }
}

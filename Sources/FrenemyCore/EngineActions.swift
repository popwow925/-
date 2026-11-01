import Foundation

// Player-driven actions. Every action is validated against the current phase and bumps stateVersion.
extension GameEngine {
    // MARK: Mini-game scores

    @discardableResult
    public func submitScore(_ player: PlayerID, _ score: Int) -> Bool {
        guard phase == .miniGame || phase == .finale else { return false }
        guard players.contains(where: { $0.id == player && $0.isConnected }) else { return false }
        guard scores[player] == nil else { return false }
        scores[player] = score
        bump()
        return true
    }

    // MARK: Shop

    @discardableResult
    public func buyShield(_ player: PlayerID) -> Bool {
        guard phase == .tile, shopOpenFor.contains(player) else { return false }
        guard crystals(player) >= Board.shieldPrice else { return false }
        guard var l = ledger, l.addShield(player) else { return false }
        ledger = l
        setCrystals(player, crystals(player) - Board.shieldPrice)
        shopOpenFor.remove(player)
        events.append(PublicEvent(key: "shop.bought_shield", params: ["player": player]))
        bump()
        return true
    }

    // MARK: Sabotage

    public func playCard(_ card: CardKind, by player: PlayerID, on target: PlayerID) -> PlayResult {
        guard phase == .sabotage, var l = ledger else { return .rejected(.wrongPhase) }
        let result = l.play(card: card,
                            by: player,
                            on: target,
                            round: round,
                            totalRounds: config.totalRounds,
                            strongEnabled: config.strongSabotageEnabled)
        ledger = l
        switch result {
        case .applied:
            applyCardEffect(card, by: player, target: target)
        case .blockedByShield:
            events.append(PublicEvent(key: "card.blocked", params: ["card": card.rawValue, "by": player, "target": target]))
        case .rejected:
            break
        }
        bump()
        return result
    }

    func applyCardEffect(_ card: CardKind, by player: PlayerID, target: PlayerID) {
        events.append(PublicEvent(key: "card.played", params: ["card": card.rawValue, "by": player, "target": target]))
        switch card {
        case .reverseDice:
            reversedNext.insert(target)
        case .leftHandRule:
            activeRules[target] = "rule.left_hand"
        case .swapNickname:
            let ids = GameEngine.funNicknameIDs
            nicknameOverride[target] = ids[random.nextInt(upperBound: ids.count)]
        case .stealCrystals:
            let amount = min(3, crystals(target))
            setCrystals(target, crystals(target) - amount)
            setCrystals(player, crystals(player) + amount)
        case .skipShop:
            skipShopNext.insert(target)
        }
    }

    // MARK: Final bet

    public func placeBet(_ bet: Bet) -> Result<Void, BetError> {
        guard phase == .betting, var book = bets else { return .failure(.wrongPhase) }
        var table: [PlayerID: Int] = [:]
        for p in players { table[p.id] = p.crystals }
        let standingsBefore = Ranking.compete(scores: table)
        let result = book.place(bet, crystals: &table, ranks: standingsBefore)
        if case .success = result {
            bets = book
            for (id, value) in table { setCrystals(id, value) }
            bump()
        }
        return result
    }

    // MARK: Awards (谁最可能)

    private func mutateAward(_ change: (inout AwardRound) -> AwardError?, whenMissing: AwardError) -> AwardError? {
        guard phase == .award, var a = award else { return whenMissing }
        let error = change(&a)
        award = a
        bump()
        return error
    }

    public func voteAward(voter: PlayerID, target: PlayerID) -> AwardError? {
        mutateAward({ $0.vote(voter: voter, target: target) }, whenMissing: .finalized)
    }

    public func awardAccept(_ player: PlayerID) -> AwardError? {
        mutateAward({ $0.accept(player) }, whenMissing: .notAWinner)
    }

    public func awardDecline(_ player: PlayerID) -> AwardError? {
        mutateAward({ $0.decline(player) }, whenMissing: .notAWinner)
    }

    public func awardReplace(_ player: PlayerID, with titleID: String) -> AwardError? {
        mutateAward({ $0.replace(player, with: titleID) }, whenMissing: .notAWinner)
    }

    public func awardShowInReport(_ player: PlayerID, _ show: Bool) -> AwardError? {
        mutateAward({ $0.setShowInReport(player, show) }, whenMissing: .notAWinner)
    }

    // MARK: Secret missions

    /// Called when the mission's condition is confirmed (host confirmation or an automatic check).
    public func confirmMissionCompleted(round missionRound: Int) {
        missionDone.insert(missionRound)
        bump()
    }

    // MARK: Moderation

    @discardableResult
    public func submitReport(reporter: PlayerID, target: PlayerID, content: ReportedContent) -> Report? {
        guard playerIDs.contains(reporter), playerIDs.contains(target) else { return nil }
        let r = reports.submit(reporter: reporter, target: target, content: content, round: round)
        if r != nil { bump() }
        return r
    }

    /// Host clears reported content of a player.
    public func hostClear(_ content: ReportedContent, of target: PlayerID) {
        guard let i = players.firstIndex(where: { $0.id == target }) else { return }
        switch content {
        case .nickname:
            players[i].nickname = "P\(i + 1)"
            nicknameOverride[target] = nil
        case .title:
            shownTitles[target] = []
        case .drawing, .customText:
            break      // owned by the mini-game modules that store drawings / text
        }
        bump()
    }

    public func kick(_ id: PlayerID) {
        kicked.insert(id)
        if phase == .lobby {
            players.removeAll { $0.id == id }
            positions[id] = nil
        } else if let i = players.firstIndex(where: { $0.id == id }) {
            players[i].isConnected = false
        }
        bump()
    }
}

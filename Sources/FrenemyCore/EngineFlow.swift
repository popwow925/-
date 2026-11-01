import Foundation

extension GameEngine {
    /// Crystals for a mini-game rank. Ties share the rank and therefore the reward.
    public static func payoutFor(rank: Int) -> Int {
        switch rank {
        case 1: return 5
        case 2: return 3
        case 3: return 2
        default: return 1
        }
    }

    // MARK: Tiles

    func applyTileOutcomes() {
        events = []
        for p in players where p.isConnected {
            let pos = positions[p.id] ?? 0
            switch TileRules.outcome(for: pos, random: random) {
            case .nothing:
                break
            case .crystals(let delta):
                setCrystals(p.id, crystals(p.id) + delta)
                events.append(PublicEvent(key: delta >= 0 ? "tile.crystal" : "tile.trap",
                                          params: ["player": p.id, "amount": String(abs(delta))]))
            case .teleport(let to):
                positions[p.id] = to
                events.append(PublicEvent(key: "tile.teleport", params: ["player": p.id, "to": String(to)]))
            case .shopOpen:
                if skipShopNext.remove(p.id) != nil {
                    events.append(PublicEvent(key: "tile.shop_skipped", params: ["player": p.id]))
                } else {
                    shopOpenFor.insert(p.id)
                    events.append(PublicEvent(key: "tile.shop", params: ["player": p.id]))
                }
            case .event(let kind):
                applyEvent(kind, roller: p.id)
            }
        }
    }

    func applyEvent(_ kind: EventKind, roller: PlayerID) {
        events.append(PublicEvent(key: kind.contentID, params: ["player": roller]))
        switch kind {
        case .everyoneGainsOne:
            for p in players { setCrystals(p.id, p.crystals + 1) }
        case .lastPlaceGainsTwo:
            var table: [PlayerID: Int] = [:]
            for p in players { table[p.id] = p.crystals }
            for id in SabotageRules.lastPlace(results: Ranking.compete(scores: table)) {
                setCrystals(id, crystals(id) + 2)
            }
        case .rollerGetsShield:
            _ = ledger?.addShield(roller)
        }
    }

    // MARK: Payout

    func doPayout() {
        events = []
        let n = config.totalRounds

        for r in lastResults {
            setCrystals(r.player, crystals(r.player) + GameEngine.payoutFor(rank: r.rank))
        }
        events.append(PublicEvent(key: "payout.done", params: ["round": String(round)]))

        // 内鬼任务: reward is credited now and shown only as a mystery reward.
        for i in missions.indices where missions[i].round == round && missions[i].outcome == .pending {
            let holder = missions[i].holder
            let connected = players.first(where: { $0.id == holder })?.isConnected ?? false
            let res = MissionRules.resolve(missions[i], completed: missionDone.contains(round), holderConnected: connected)
            missions[i] = res.assignment
            if res.reward > 0 {
                setCrystals(holder, crystals(holder) + res.reward)
                events.append(PublicEvent(key: "mission.mystery_reward"))
            }
        }

        if round == n {
            settleFinalBets()
            return
        }

        ledger?.grantLastPlaceShields(results: lastResults)
        ledger?.issueVouchers(results: lastResults, round: round, totalRounds: n)
        phase = .sabotage
    }

    func settleFinalBets() {
        var table: [PlayerID: Int] = [:]
        for p in players { table[p.id] = p.crystals }
        if var book = bets {
            let outcomes = book.settle(winners: finaleWinners, crystals: &table)
            bets = book
            for o in outcomes {
                events.append(PublicEvent(key: "bet.result", params: [
                    "player": o.bet.bettor,
                    "target": o.bet.target,
                    "won": o.won ? "1" : "0",
                    "credited": String(o.credited),
                ]))
            }
        }
        for (id, value) in table { setCrystals(id, value) }
        standings = Ranking.compete(scores: table)
        phase = .results
    }

    // MARK: Awards

    func startAward() {
        let prompts = library?.pack(for: config.contentLocale)?.ids(withPrefix: "prompt.most_likely.") ?? []
        let question = prompts.isEmpty ? "prompt.most_likely.001" : prompts[random.nextInt(upperBound: prompts.count)]
        let title = TitleLibrary.ids[random.nextInt(upperBound: TitleLibrary.ids.count)]
        award = AwardRound(questionID: question, titleID: title, players: playerIDs)
        events = [PublicEvent(key: question)]
        phase = .award
    }

    func commitAward() {
        guard let a = award else { return }
        for entry in a.reportEntries {
            shownTitles[entry.player, default: []].append(entry.titleID)
        }
        award = nil
    }
}

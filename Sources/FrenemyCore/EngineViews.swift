import Foundation

// Projection of internal state into the two view-model families (see Views.swift).
// publicState() is safe for the TV and the host's public console. privateState(for:) goes to ONE device.
extension GameEngine {
    public func publicState() -> PublicState {
        let shown: Set<GamePhase> = [.betting, .miniGame, .finale, .payout]
        let pub = players.map { p in
            PublicPlayer(id: p.id,
                         nickname: p.nickname,
                         avatar: p.avatar,
                         crystals: p.crystals,
                         shields: ledger?.shieldCount(p.id) ?? 0,
                         position: positions[p.id] ?? 0,
                         isConnected: p.isConnected,
                         shownTitleIDs: shownTitles[p.id] ?? [],
                         nicknameOverrideID: nicknameOverride[p.id])
        }
        return PublicState(stateVersion: stateVersion,
                           roomCode: roomCode,
                           phase: phase,
                           mode: config.mode,
                           round: round,
                           totalRounds: config.totalRounds,
                           contentLocale: config.contentLocale,
                           strongSabotageEnabled: config.strongSabotageEnabled,
                           players: pub,
                           miniGame: shown.contains(phase) ? currentMiniGame : nil,
                           events: events,
                           activeRules: activeRules,
                           standings: (phase == .results || phase == .ended) ? standings : nil,
                           revealedMissions: phase == .ended ? revealedMissions : nil)
    }

    public func privateState(for id: PlayerID) -> PrivateState? {
        guard players.contains(where: { $0.id == id }) else { return nil }

        let vouchers: [VoucherView] = (ledger?.vouchers ?? [])
            .filter { $0.owner == id }
            .map { VoucherView(id: $0.id, issuedRound: $0.issuedRound) }

        let missionPhases: Set<GamePhase> = [.roundStart, .roll, .move, .tile, .miniGame, .payout]
        var mission: PrivateMission?
        if missionPhases.contains(phase),
           let m = missions.first(where: { $0.holder == id && $0.round == round && $0.outcome == .pending }) {
            mission = PrivateMission(round: m.round, missionID: m.missionID)
        }

        var prompt: PrivateAwardPrompt?
        if phase == .award, let a = award, a.isFinalized,
           a.winners.contains(where: { $0.player == id && $0.status == .pending }) {
            prompt = PrivateAwardPrompt(titleID: a.titleID)
        }

        return PrivateState(stateVersion: stateVersion,
                            player: id,
                            vouchers: vouchers,
                            mission: mission,
                            word: nil,                 // 谁是卧底 arrives in M3
                            awardPrompt: prompt,
                            bet: bets?.bets[id])
    }
}

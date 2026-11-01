import Foundation
import FrenemyCore

/// A scripted player. It talks to the host only through a ClientSession, exactly like a real phone.
public final class BotClient {
    public let session: ClientSession
    private let random: RandomSource
    private var done: Set<String> = []

    public init(session: ClientSession, random: RandomSource) {
        self.session = session
        self.random = random
        session.onPublicState = { [weak self] _ in self?.act() }
        session.onPrivateState = { [weak self] _ in self?.act() }
    }

    public var id: PlayerID { session.player.id }

    /// Acts once per (key). Requires public and private state of the SAME version so decisions
    /// never use a stale hand.
    private func act() {
        guard let pub = session.publicState,
              let priv = session.privateState,
              priv.stateVersion == pub.stateVersion else { return }

        switch pub.phase {
        case .miniGame, .finale:
            once("score-\(pub.round)-\(pub.phase.rawValue)") {
                session.send(.score(random.nextInt(upperBound: 100)))
            }

        case .tile:
            let shopEvent = pub.events.contains { $0.key == "tile.shop" && $0.params["player"] == id }
            let mine = pub.players.first(where: { $0.id == id })?.crystals ?? 0
            if shopEvent, mine >= Board.shieldPrice {
                once("shop-\(pub.round)") { session.send(.buyShield) }
            }

        case .sabotage:
            let usable = priv.vouchers.contains { pub.round >= $0.usableFromRound && pub.round <= $0.lastUsableRound }
            let others = pub.players.filter { $0.id != id }
            if usable, pub.round < pub.totalRounds, !others.isEmpty {
                once("card-\(pub.round)") {
                    let target = others[random.nextInt(upperBound: others.count)].id
                    session.send(.playCard(.reverseDice, target: target))
                }
            }

        case .award:
            let others = pub.players.filter { $0.id != id }
            if !others.isEmpty {
                once("vote-\(pub.round)") {
                    session.send(.awardVote(target: others[random.nextInt(upperBound: others.count)].id))
                }
            }
            if priv.awardPrompt != nil {
                once("accept-\(pub.round)") { session.send(.awardAccept) }
            }

        case .betting:
            let others = pub.players.filter { $0.id != id }
            guard let me = pub.players.first(where: { $0.id == id }), !others.isEmpty else { return }
            var table: [PlayerID: Int] = [:]
            for p in pub.players { table[p.id] = p.crystals }
            let rank = Ranking.compete(scores: table).first(where: { $0.player == id })?.rank ?? pub.players.count
            let cap = FinalBetRules.maxStake(crystals: me.crystals, rank: rank, playerCount: pub.players.count)
            if cap >= 1 {
                once("bet-\(pub.round)") {
                    let target = others[random.nextInt(upperBound: others.count)].id
                    session.send(.bet(target: target, stake: cap))
                }
            }

        default:
            break
        }
    }

    private func once(_ key: String, _ work: () -> Void) {
        guard !done.contains(key) else { return }
        done.insert(key)
        work()
    }
}

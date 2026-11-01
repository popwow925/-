import Foundation

public enum TileKind: String, Codable, CaseIterable, Sendable {
    case start, crystal, trap, event, shop, teleport, challenge
}

public enum EventKind: String, Codable, CaseIterable, Sendable {
    case everyoneGainsOne        // event.everyone_gains_one
    case lastPlaceGainsTwo       // event.last_place_gains_two
    case rollerGetsShield        // event.roller_gets_shield

    public var contentID: String {
        switch self {
        case .everyoneGainsOne: return "event.everyone_gains_one"
        case .lastPlaceGainsTwo: return "event.last_place_gains_two"
        case .rollerGetsShield: return "event.roller_gets_shield"
        }
    }
}

/// A ring of 24 tiles. Original layout; no resemblance to any existing board game map.
public enum Board {
    public static let size = 24
    public static let crystalTileReward = 3
    public static let trapTilePenalty = 3
    public static let challengeReward = 2
    public static let teleportDistance = 12
    public static let shieldPrice = 5

    public static let layout: [TileKind] = [
        .start, .crystal, .crystal, .event, .trap, .crystal,
        .shop, .challenge, .crystal, .teleport, .crystal, .trap,
        .event, .crystal, .crystal, .trap, .shop, .challenge,
        .crystal, .event, .trap, .crystal, .teleport, .crystal,
    ]

    public static func tile(at position: Int) -> TileKind {
        layout[((position % size) + size) % size]
    }

    /// Moves along the ring. `reversed` implements the "反转骰子方向" card.
    public static func advance(from position: Int, steps: Int, reversed: Bool) -> Int {
        let delta = reversed ? -steps : steps
        return (((position + delta) % size) + size) % size
    }

    public static func rollDie(using random: RandomSource) -> Int {
        random.nextInt(upperBound: 6) + 1
    }
}

public enum TileOutcome: Equatable, Sendable {
    case crystals(Int)                 // positive or negative; floor at 0 is applied by the engine
    case teleport(to: Int)
    case shopOpen
    case event(EventKind)
    case nothing
}

public enum TileRules {
    public static func outcome(for position: Int, random: RandomSource) -> TileOutcome {
        switch Board.tile(at: position) {
        case .start: return .nothing
        case .crystal: return .crystals(Board.crystalTileReward)
        case .trap: return .crystals(-Board.trapTilePenalty)
        case .challenge: return .crystals(Board.challengeReward)
        case .shop: return .shopOpen
        case .teleport: return .teleport(to: Board.advance(from: position, steps: Board.teleportDistance, reversed: false))
        case .event:
            let all = EventKind.allCases
            return .event(all[random.nextInt(upperBound: all.count)])
        }
    }
}

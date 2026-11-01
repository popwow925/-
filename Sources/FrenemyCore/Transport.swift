import Foundation

public enum Reliability: Sendable {
    case reliable      // join, vote, settle
    case unreliable    // high-frequency motion summaries
}

public enum TransportEvent: Sendable {
    case peerConnected(PlayerID)
    case peerDisconnected(PlayerID)
    case received(from: PlayerID, Envelope)
}

/// Abstraction that both the real Multipeer layer (App target) and the offline
/// LoopbackTransport (FrenemyDemo) implement. The game engine only sees this.
public protocol GameTransport: AnyObject {
    /// Called by the transport for every event. Set by the engine/client before `start()`.
    var onEvent: ((TransportEvent) -> Void)? { get set }

    func start()
    func stop()

    /// Send to one peer.
    func send(_ envelope: Envelope, to peer: PlayerID, reliability: Reliability)
    /// Send to every connected peer.
    func broadcast(_ envelope: Envelope, reliability: Reliability)
    /// Host-side: drop a peer.
    func disconnect(_ peer: PlayerID)
}

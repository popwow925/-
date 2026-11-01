import Foundation
import FrenemyCore

// Fully offline, in-memory transport used by 单机演示 and by tests.
// It never creates MultipeerConnectivity objects, never does Bonjour discovery and never touches
// the network, so it cannot trigger the local-network permission prompt.
// This module depends on FrenemyCore only (not on the App's Multipeer layer).

public final class LoopbackHub {
    public static let hostID: PlayerID = "host"

    fileprivate var endpoints: [PlayerID: LoopbackTransport] = [:]
    fileprivate var hostStarted = false
    private var queue: [() -> Void] = []
    private var draining = false

    public init() {}

    public func makeHostTransport() -> LoopbackTransport {
        let t = LoopbackTransport(id: LoopbackHub.hostID, isHost: true, hub: self)
        endpoints[LoopbackHub.hostID] = t
        return t
    }

    public func makeClientTransport(id: PlayerID) -> LoopbackTransport {
        let t = LoopbackTransport(id: id, isHost: false, hub: self)
        endpoints[id] = t
        return t
    }

    /// Delivery is synchronous but re-entrancy safe: work queued while draining runs after the
    /// current item, in FIFO order.
    fileprivate func enqueue(_ work: @escaping () -> Void) {
        queue.append(work)
        guard !draining else { return }
        draining = true
        while !queue.isEmpty {
            let next = queue.removeFirst()
            next()
        }
        draining = false
    }
}

public final class LoopbackTransport: GameTransport {
    public var onEvent: ((TransportEvent) -> Void)?

    let id: PlayerID
    private let isHost: Bool
    private unowned let hub: LoopbackHub
    private var connectedClients: Set<PlayerID> = []
    private var connectedToHost = false

    init(id: PlayerID, isHost: Bool, hub: LoopbackHub) {
        self.id = id
        self.isHost = isHost
        self.hub = hub
    }

    public func start() {
        if isHost {
            hub.hostStarted = true
            return
        }
        guard hub.hostStarted, let host = hub.endpoints[LoopbackHub.hostID] else { return }
        connectedToHost = true
        let me = id
        hub.enqueue { [weak host, weak self] in
            host?.connectedClients.insert(me)
            host?.onEvent?(.peerConnected(me))
            self?.onEvent?(.peerConnected(LoopbackHub.hostID))
        }
    }

    public func stop() {
        let me = id
        if isHost {
            hub.hostStarted = false
            let clients = connectedClients
            connectedClients.removeAll()
            for c in clients {
                let target = hub.endpoints[c]
                hub.enqueue { [weak target] in
                    target?.connectedToHost = false
                    target?.onEvent?(.peerDisconnected(LoopbackHub.hostID))
                }
            }
        } else if connectedToHost {
            connectedToHost = false
            let host = hub.endpoints[LoopbackHub.hostID]
            hub.enqueue { [weak host] in
                host?.connectedClients.remove(me)
                host?.onEvent?(.peerDisconnected(me))
            }
        }
    }

    public func send(_ envelope: Envelope, to peer: PlayerID, reliability: Reliability) {
        guard let copy = roundTrip(envelope), let target = hub.endpoints[peer] else { return }
        let from = id
        hub.enqueue { [weak target] in
            target?.onEvent?(.received(from: from, copy))
        }
    }

    public func broadcast(_ envelope: Envelope, reliability: Reliability) {
        if isHost {
            for c in connectedClients.sorted() { send(envelope, to: c, reliability: reliability) }
        } else {
            send(envelope, to: LoopbackHub.hostID, reliability: reliability)
        }
    }

    public func disconnect(_ peer: PlayerID) {
        guard isHost, connectedClients.remove(peer) != nil, let target = hub.endpoints[peer] else { return }
        hub.enqueue { [weak target] in
            target?.connectedToHost = false
            target?.onEvent?(.peerDisconnected(LoopbackHub.hostID))
        }
    }

    /// Serializes and deserializes like a real wire would, so Codable bugs show up offline too.
    private func roundTrip(_ e: Envelope) -> Envelope? {
        guard let data = try? Codec.encode(e) else { return nil }
        return try? Codec.decode(Envelope.self, from: data)
    }
}

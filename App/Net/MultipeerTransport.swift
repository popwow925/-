import Foundation
import MultipeerConnectivity
import FrenemyCore

// MultipeerConnectivity implementation of GameTransport.
//
// Rules this file follows (see party-plan.md §Apple 合规):
//  - Nothing here is created until the user enters 创建房间 / 加入房间. 单机演示 never touches this file,
//    so the local-network permission prompt only appears when a real room is used.
//  - MCPeerID.displayName == PlayerID on player devices. The host's displayName is only a label.
//  - MC delivers callbacks on private queues; every event is hopped to the main queue, because the
//    game engine is not thread-safe.
//  - Encryption is required. No security identity (LAN party, no accounts).

enum MultipeerConfig {
    /// <= 15 chars, lowercase letters/digits/hyphen. Must match NSBonjourServices (_frenemy._tcp / _udp).
    static let serviceType = "frenemy"
    static let invitationTimeout: TimeInterval = 15
    /// Soft cap on simultaneous MC connections. The engine still enforces Limits.maxPlayers
    /// and answers with RejectReason.roomFull.
    static let maxConnections = Limits.maxPlayers + 2
}

struct DiscoveredRoom: Identifiable, Equatable {
    let id: String               // host peer displayName
    let roomCode: String
    let hostName: String
    let protocolVersion: Int
    let contentVersion: Int
    let peer: MCPeerID

    static func == (a: DiscoveredRoom, b: DiscoveredRoom) -> Bool {
        a.id == b.id && a.roomCode == b.roomCode && a.hostName == b.hostName
    }
}

final class MultipeerTransport: NSObject, GameTransport {
    enum Role {
        case host(roomCode: String, hostName: String, contentVersion: Int)
        case client
    }

    var onEvent: ((TransportEvent) -> Void)?
    /// Client role only: called on the main queue whenever the list of nearby rooms changes.
    var onRoomsChanged: (([DiscoveredRoom]) -> Void)?
    /// Called on the main queue for errors the UI may want to show (advertising/browsing failed).
    var onFailure: ((String) -> Void)?

    private let role: Role
    private let myPeer: MCPeerID
    private let session: MCSession
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?
    private var peers: [String: MCPeerID] = [:]        // displayName -> peer (connected only)
    private var rooms: [String: DiscoveredRoom] = [:]
    private var running = false

    /// `displayName` must be <= 63 bytes (MC limit). Player IDs are short random ids.
    init(role: Role, displayName: String) {
        let peer = MCPeerID(displayName: String(displayName.prefix(40)))
        self.role = role
        self.myPeer = peer
        self.session = MCSession(peer: peer, securityIdentity: nil, encryptionPreference: .required)
        super.init()
        session.delegate = self
    }

    // MARK: GameTransport

    func start() {
        guard !running else { return }
        running = true
        switch role {
        case .host(let code, let hostName, let contentVersion):
            let info: [String: String] = [
                "r": code,
                "n": String(hostName.prefix(24)),
                "p": String(WireProtocol.version),
                "c": String(contentVersion)
            ]
            let adv = MCNearbyServiceAdvertiser(peer: myPeer, discoveryInfo: info, serviceType: MultipeerConfig.serviceType)
            adv.delegate = self
            adv.startAdvertisingPeer()
            advertiser = adv
        case .client:
            let b = MCNearbyServiceBrowser(peer: myPeer, serviceType: MultipeerConfig.serviceType)
            b.delegate = self
            b.startBrowsingForPeers()
            browser = b
        }
    }

    func stop() {
        running = false
        advertiser?.stopAdvertisingPeer()
        advertiser = nil
        browser?.stopBrowsingForPeers()
        browser = nil
        session.disconnect()
        peers.removeAll()
        rooms.removeAll()
    }

    /// Stop advertising once the game has started (late joiners are rejected anyway, and a hidden room
    /// is calmer for neighbours on the same Wi-Fi). Existing connections stay up.
    func stopAdvertising() {
        advertiser?.stopAdvertisingPeer()
        advertiser = nil
    }

    func send(_ envelope: Envelope, to peer: PlayerID, reliability: Reliability) {
        guard let target = peers[peer] else { return }
        push(envelope, to: [target], reliability: reliability)
    }

    func broadcast(_ envelope: Envelope, reliability: Reliability) {
        let targets = Array(peers.values)
        guard !targets.isEmpty else { return }
        push(envelope, to: targets, reliability: reliability)
    }

    func disconnect(_ peer: PlayerID) {
        guard let target = peers[peer] else { return }
        // Give the reject/kick message a moment to leave before the link is cut.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            self?.session.cancelConnectPeer(target)
        }
    }

    // MARK: Client role

    /// Client role: ask a discovered host to let us in. The result arrives as `.peerConnected`.
    func connect(to room: DiscoveredRoom) {
        guard case .client = role, let browser = browser else { return }
        browser.invitePeer(room.peer, to: session, withContext: nil, timeout: MultipeerConfig.invitationTimeout)
    }

    // MARK: Private

    private func push(_ envelope: Envelope, to targets: [MCPeerID], reliability: Reliability) {
        guard let data = try? Codec.encode(envelope) else { return }
        let mode: MCSessionSendDataMode = (reliability == .reliable) ? .reliable : .unreliable
        do {
            try session.send(data, toPeers: targets, with: mode)
        } catch {
            // A failed send is treated like a lost packet; reliable state is re-sent on the next broadcast.
        }
    }

    private func emit(_ event: TransportEvent) {
        DispatchQueue.main.async { [weak self] in self?.onEvent?(event) }
    }

    private func publishRooms() {
        let list = rooms.values.sorted { $0.roomCode < $1.roomCode }
        DispatchQueue.main.async { [weak self] in self?.onRoomsChanged?(list) }
    }
}

// MARK: - MCSessionDelegate

extension MultipeerTransport: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        let name = peerID.displayName
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            switch state {
            case .connected:
                if case .host = self.role, self.peers.count >= MultipeerConfig.maxConnections {
                    session.cancelConnectPeer(peerID)
                    return
                }
                self.peers[name] = peerID
                self.onEvent?(.peerConnected(name))
            case .notConnected:
                guard self.peers.removeValue(forKey: name) != nil else { return }
                self.onEvent?(.peerDisconnected(name))
            case .connecting:
                break
            @unknown default:
                break
            }
        }
    }

    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        guard let env = try? Codec.decode(Envelope.self, from: data) else { return }
        emit(.received(from: peerID.displayName, env))
    }

    // Streams and resources are not used.
    func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

// MARK: - Advertiser (host)

extension MultipeerTransport: MCNearbyServiceAdvertiserDelegate {
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser,
                    didReceiveInvitationFromPeer peerID: MCPeerID,
                    withContext context: Data?,
                    invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        // Always accept at the transport level; GameEngine.join decides (room full, game in progress,
        // version mismatch, kicked) and answers with a reject message the player can read.
        invitationHandler(true, session)
    }

    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        let text = (error as NSError).localizedDescription
        DispatchQueue.main.async { [weak self] in self?.onFailure?(text) }
    }
}

// MARK: - Browser (client)

extension MultipeerTransport: MCNearbyServiceBrowserDelegate {
    func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String: String]?) {
        guard let info = info, let code = info["r"] else { return }
        let room = DiscoveredRoom(id: peerID.displayName,
                                  roomCode: code,
                                  hostName: info["n"] ?? "",
                                  protocolVersion: Int(info["p"] ?? "") ?? 0,
                                  contentVersion: Int(info["c"] ?? "") ?? 0,
                                  peer: peerID)
        DispatchQueue.main.async { [weak self] in
            self?.rooms[room.id] = room
            self?.publishRooms()
        }
    }

    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        let id = peerID.displayName
        DispatchQueue.main.async { [weak self] in
            self?.rooms.removeValue(forKey: id)
            self?.publishRooms()
        }
    }

    func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
        let text = (error as NSError).localizedDescription
        DispatchQueue.main.async { [weak self] in self?.onFailure?(text) }
    }
}

import Foundation

/// Injectable randomness so rules and the offline demo are deterministic in tests.
public protocol RandomSource: AnyObject {
    /// Uniform integer in 0..<upperBound. `upperBound` must be > 0.
    func nextInt(upperBound: Int) -> Int
}

/// SplitMix64: tiny, fast, deterministic. Not for security.
public final class SeededRandom: RandomSource {
    private var state: UInt64

    public init(seed: UInt64) { state = seed }

    private func next64() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    public func nextInt(upperBound: Int) -> Int {
        precondition(upperBound > 0)
        return Int(next64() % UInt64(upperBound))
    }
}

/// Production source backed by the system generator.
public final class SystemRandom: RandomSource {
    public init() {}
    public func nextInt(upperBound: Int) -> Int {
        precondition(upperBound > 0)
        var g = SystemRandomNumberGenerator()
        return Int.random(in: 0..<upperBound, using: &g)
    }
}

public extension Array {
    /// Fisher-Yates using an injected source.
    func shuffled(using source: RandomSource) -> [Element] {
        var a = self
        guard a.count > 1 else { return a }
        for i in stride(from: a.count - 1, to: 0, by: -1) {
            let j = source.nextInt(upperBound: i + 1)
            a.swapAt(i, j)
        }
        return a
    }
}

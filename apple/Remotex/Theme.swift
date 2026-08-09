import SwiftUI
import UIKit

// Two palettes, one token set — mirrors apps/web/src/styles.css and the
// Android RemotexPalette.
//
// Each token is a dynamic UIColor, so light mode is automatic: the system
// resolves the right variant per trait collection, and an explicit user
// override just flips `preferredColorScheme` at the root.

private func dyn(dark: UInt32, light: UInt32) -> Color {
    Color(UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
    })
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    static let remotexBackground = dyn(dark: 0x050910, light: 0xF6F8FB)
    static let remotexSurface = dyn(dark: 0x0A1120, light: 0xFFFFFF)
    static let remotexSurface2 = dyn(dark: 0x121A2C, light: 0xEEF2F8)
    static let remotexLine = dyn(dark: 0x1D2940, light: 0xD7DFEB)
    static let remotexText = dyn(dark: 0xE3EDFA, light: 0x101B2C)
    static let remotexMuted = dyn(dark: 0x88A4C4, light: 0x51677F)
    static let remotexAccent = dyn(dark: 0x5EE1FF, light: 0x007C9E)
    static let remotexAccentDeep = dyn(dark: 0x3AA0E8, light: 0x1668C7)
    static let remotexGreen = dyn(dark: 0x6AE0C2, light: 0x0D7F66)
    static let remotexBlue = dyn(dark: 0x8FB4FF, light: 0x2458B3)
    static let remotexWarn = dyn(dark: 0xFF7070, light: 0xD92D20)
    // Syntax highlighting + diff tints.
    static let remotexCodeString = dyn(dark: 0x9CD4A0, light: 0x1A7F37)
    static let remotexCodeNumber = dyn(dark: 0xE0B479, light: 0xA15C07)
    static let remotexCodeKeyword = dyn(dark: 0x5EE1FF, light: 0x007C9E)
}

/// Theme preference: follow the system, or an explicit override the user
/// picked. Persisted so it survives relaunches.
enum ThemeChoice: String {
    case system, light, dark

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

final class ThemeSetting: ObservableObject {
    private static let key = "remotex.theme"

    @Published var choice: ThemeChoice {
        didSet { UserDefaults.standard.set(choice.rawValue, forKey: Self.key) }
    }

    init() {
        let raw = UserDefaults.standard.string(forKey: Self.key) ?? ThemeChoice.system.rawValue
        choice = ThemeChoice(rawValue: raw) ?? .system
    }

    /// Cycles system → dark → light → system, so one button covers all three.
    func advance() {
        choice = switch choice {
        case .system: .dark
        case .dark: .light
        case .light: .system
        }
    }

    var iconName: String {
        switch choice {
        case .system: return "circle.lefthalf.filled"
        case .dark: return "moon.fill"
        case .light: return "sun.max.fill"
        }
    }
}


extension String {
    /// `self` unless empty, in which case the fallback.
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}

import SwiftUI
import UIKit

// Two palettes, one token set — mirrors apps/web/src/styles.css and the
// Android RemotexPalette.
//
// Each token is a dynamic UIColor, so light mode and the system Increase
// Contrast setting are automatic. Explicit user light/dark choices flip
// `preferredColorScheme`; explicit High Contrast also applies a root contrast
// boost in ContentView because iOS does not permit apps to override the
// system-owned `colorSchemeContrast` environment value.

private func dyn(
    dark: UInt32,
    light: UInt32,
    highDark: UInt32,
    highLight: UInt32
) -> Color {
    Color(UIColor { traits in
        if traits.accessibilityContrast == .high {
            return UIColor(rgb: traits.userInterfaceStyle == .dark ? highDark : highLight)
        }
        return traits.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
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
    static let remotexBackground = dyn(dark: 0x050910, light: 0xF6F8FB, highDark: 0x000000, highLight: 0xFFFFFF)
    static let remotexSurface = dyn(dark: 0x0A1120, light: 0xFFFFFF, highDark: 0x080808, highLight: 0xFFFFFF)
    static let remotexSurface2 = dyn(dark: 0x121A2C, light: 0xEEF2F8, highDark: 0x111111, highLight: 0xF0F0F0)
    static let remotexLine = dyn(dark: 0x1D2940, light: 0xD7DFEB, highDark: 0xFFFFFF, highLight: 0x000000)
    static let remotexText = dyn(dark: 0xE3EDFA, light: 0x101B2C, highDark: 0xFFFFFF, highLight: 0x000000)
    static let remotexMuted = dyn(dark: 0x88A4C4, light: 0x51677F, highDark: 0xD8D8D8, highLight: 0x303030)
    static let remotexAccent = dyn(dark: 0x5EE1FF, light: 0x007C9E, highDark: 0x00FFFF, highLight: 0x005FCC)
    static let remotexAccentDeep = dyn(dark: 0x3AA0E8, light: 0x1668C7, highDark: 0x78B8FF, highLight: 0x003E99)
    static let remotexGreen = dyn(dark: 0x6AE0C2, light: 0x0D7F66, highDark: 0x70FFBF, highLight: 0x006644)
    static let remotexBlue = dyn(dark: 0x8FB4FF, light: 0x2458B3, highDark: 0xA8C7FF, highLight: 0x003E99)
    static let remotexWarn = dyn(dark: 0xFF7070, light: 0xD92D20, highDark: 0xFF7373, highLight: 0xB00000)
    // Syntax highlighting + diff tints.
    static let remotexCodeString = dyn(dark: 0x9CD4A0, light: 0x1A7F37, highDark: 0x9CFFAA, highLight: 0x006622)
    static let remotexCodeNumber = dyn(dark: 0xE0B479, light: 0xA15C07, highDark: 0xFFD27A, highLight: 0x7A3E00)
    static let remotexCodeKeyword = dyn(dark: 0x5EE1FF, light: 0x007C9E, highDark: 0x00FFFF, highLight: 0x005FCC)
}

/// Theme preference: follow the system, or an explicit override the user
/// picked. Persisted so it survives relaunches.
enum ThemeChoice: String {
    case system, light, dark, highContrast

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        case .highContrast: return .dark
        }
    }

    var displayName: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        case .highContrast: return "High contrast"
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

    /// One compact control covers every supported palette.
    func advance() {
        choice = switch choice {
        case .system: .dark
        case .dark: .light
        case .light: .highContrast
        case .highContrast: .system
        }
    }

    var iconName: String {
        switch choice {
        case .system: return "circle.lefthalf.filled"
        case .dark: return "moon.fill"
        case .light: return "sun.max.fill"
        case .highContrast: return "circle.righthalf.filled"
        }
    }
}


extension String {
    /// `self` unless empty, in which case the fallback.
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}

import Foundation
import Security

/// Minimal generic-password wrapper for relay-scoped bearer credentials.
/// UserDefaults writes plaintext into an unencrypted backup, so the bearer
/// token lives here instead.
enum Keychain {
    private static let service = "app.remotex.ios"

    static func string(for account: String) -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    static func set(_ value: String, for account: String) -> Bool {
        guard !value.isEmpty else {
            return remove(account: account)
        }
        let query = baseQuery(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            // Background reconnect works after first unlock, but the bearer
            // token must never migrate to another device through a backup.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess {
            return true
        }
        guard status == errSecItemNotFound else {
            return false
        }
        var insert = query
        for (key, attribute) in attributes {
            insert[key] = attribute
        }
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    @discardableResult
    static func remove(account: String) -> Bool {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private static func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

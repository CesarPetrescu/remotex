import SwiftUI

// Edit diffs the way Claude Code / Codex show them: one card per file with
// verb + path header, +N/−M counts, and green/red-tinted lines.
//
// Input contract (services/daemon/adapters/items.py::_format_changes):
//   summary — one "verb /path" line per file (arrives as the tool's detail)
//   diff    — single file: the raw unified diff;
//             multiple files: sections prefixed with "--- /path".

private struct DiffFile: Identifiable {
    let id = UUID()
    let verb: String
    let path: String
    let movedTo: String?
    let lines: [String]
}

private let collapsedLines = 14

private func parseDiffFiles(summary: String, diff: String) -> [DiffFile] {
    let verbs: [(verb: String, path: String, movedTo: String?)] = summary
        .components(separatedBy: "\n")
        .compactMap { raw in
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { return nil }
            let parts = line.split(separator: " ", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { return nil }
            let rest = parts[1]
            if let arrow = rest.range(of: " → ") {
                return (parts[0], String(rest[..<arrow.lowerBound]), String(rest[arrow.upperBound...]))
            }
            return (parts[0], rest, nil)
        }

    let allLines = diff.components(separatedBy: "\n")
    guard verbs.count > 1 else {
        let v = verbs.first
        return [DiffFile(
            verb: v?.verb ?? "update",
            path: v?.path ?? "",
            movedTo: v?.movedTo,
            lines: allLines
        )]
    }

    var files: [DiffFile] = []
    var currentVerb: (verb: String, path: String, movedTo: String?)?
    var buffer: [String] = []
    func flush() {
        guard let v = currentVerb else { return }
        files.append(DiffFile(verb: v.verb, path: v.path, movedTo: v.movedTo, lines: buffer))
        buffer = []
    }
    for line in allLines {
        if line.hasPrefix("--- ") {
            let path = String(line.dropFirst(4))
            if let match = verbs.first(where: { $0.path == path }) {
                flush()
                currentVerb = match
                continue
            }
        }
        buffer.append(line)
    }
    flush()
    return files.isEmpty
        ? [DiffFile(verb: "update", path: "", movedTo: nil, lines: allLines)]
        : files
}

struct DiffView: View {
    let summary: String
    let diff: String
    let streaming: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(parseDiffFiles(summary: summary, diff: diff)) { file in
                FileDiffCard(file: file, streaming: streaming)
            }
        }
    }
}

private struct FileDiffCard: View {
    fileprivate let file: DiffFile
    let streaming: Bool
    @State private var expanded = false

    private var adds: Int {
        file.lines.filter { $0.hasPrefix("+") && !$0.hasPrefix("+++") }.count
    }
    private var dels: Int {
        file.lines.filter { $0.hasPrefix("-") && !$0.hasPrefix("---") }.count
    }
    private var overflow: Bool { file.lines.count > collapsedLines }
    private var shown: [String] {
        if expanded || !overflow { return file.lines }
        // While streaming, the tail is the part being written.
        return streaming ? Array(file.lines.suffix(collapsedLines)) : Array(file.lines.prefix(collapsedLines))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                if overflow { expanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Text(file.verb)
                        .foregroundStyle(Color.remotexMuted)
                    Text(file.path + (file.movedTo.map { " → \($0)" } ?? ""))
                        .foregroundStyle(Color.remotexText)
                        .lineLimit(1)
                        .truncationMode(.head)
                    Spacer(minLength: 4)
                    if adds > 0 {
                        Text("+\(adds)").foregroundStyle(Color.remotexGreen)
                    }
                    if dels > 0 {
                        Text("−\(dels)").foregroundStyle(Color.remotexWarn)
                    }
                    if overflow {
                        Text(expanded ? "collapse" : "expand")
                            .foregroundStyle(Color.remotexMuted)
                    }
                }
                .font(.system(size: 11, design: .monospaced))
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.remotexSurface2)
            }
            .buttonStyle(.plain)

            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.offset) { _, line in
                        Text(line.isEmpty ? " " : line)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(color(for: line))
                            .padding(.horizontal, 9)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(background(for: line))
                    }
                    if !expanded, overflow {
                        Button("… \(file.lines.count - collapsedLines) more lines") {
                            expanded = true
                        }
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(Color.remotexAccent)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 2)
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .background(Color.remotexSurface2.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func color(for line: String) -> Color {
        if line.hasPrefix("+++") || line.hasPrefix("---") { return .remotexMuted }
        if line.hasPrefix("@@") { return .remotexAccentDeep }
        if line.hasPrefix("+") { return .remotexGreen }
        if line.hasPrefix("-") { return .remotexWarn }
        return .remotexMuted
    }

    private func background(for line: String) -> Color {
        if line.hasPrefix("+"), !line.hasPrefix("+++") { return Color.remotexGreen.opacity(0.10) }
        if line.hasPrefix("-"), !line.hasPrefix("---") { return Color.remotexWarn.opacity(0.09) }
        return .clear
    }
}

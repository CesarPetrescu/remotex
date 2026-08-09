import SwiftUI

// Markdown for the transcript: block splitting done here, inline spans via
// Apple's own AttributedString(markdown:), fenced code through the shared
// highlighter below.
//
// ponytail: no markdown library. AttributedString already handles emphasis,
// code spans and links; all that's missing is block structure and code
// fences, which is ~80 lines.

enum MarkdownBlock: Identifiable {
    case paragraph(String)
    case heading(Int, String)
    case bullets([String])
    case code(String)

    var id: String {
        switch self {
        case let .paragraph(t): return "p:\(t.prefix(40)):\(t.count)"
        case let .heading(l, t): return "h\(l):\(t.prefix(40))"
        case let .bullets(items): return "u:\(items.count):\(items.first?.prefix(24) ?? "")"
        case let .code(t): return "c:\(t.prefix(40)):\(t.count)"
        }
    }
}

func parseMarkdownBlocks(_ text: String) -> [MarkdownBlock] {
    var blocks: [MarkdownBlock] = []
    let lines = text.components(separatedBy: "\n")
    var i = 0
    while i < lines.count {
        let line = lines[i]
        let trimmed = line.trimmingCharacters(in: .whitespaces)

        if trimmed.hasPrefix("```") {
            var body: [String] = []
            i += 1
            while i < lines.count, !lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                body.append(lines[i])
                i += 1
            }
            i += 1 // closing fence (or EOF for a still-streaming block)
            blocks.append(.code(body.joined(separator: "\n")))
            continue
        }

        if trimmed.isEmpty {
            i += 1
            continue
        }

        if let hashes = trimmed.range(of: "^#{1,6} ", options: .regularExpression) {
            let level = trimmed.distance(from: trimmed.startIndex, to: hashes.upperBound) - 1
            blocks.append(.heading(level, String(trimmed[hashes.upperBound...])))
            i += 1
            continue
        }

        if isBullet(trimmed) {
            var items: [String] = []
            while i < lines.count, isBullet(lines[i].trimmingCharacters(in: .whitespaces)) {
                items.append(stripBullet(lines[i].trimmingCharacters(in: .whitespaces)))
                i += 1
            }
            blocks.append(.bullets(items))
            continue
        }

        var buf: [String] = []
        while i < lines.count {
            let l = lines[i].trimmingCharacters(in: .whitespaces)
            if l.isEmpty || l.hasPrefix("```") || isBullet(l)
                || l.range(of: "^#{1,6} ", options: .regularExpression) != nil {
                break
            }
            buf.append(lines[i])
            i += 1
        }
        blocks.append(.paragraph(buf.joined(separator: "\n")))
    }
    return blocks
}

private func isBullet(_ line: String) -> Bool {
    line.hasPrefix("- ") || line.hasPrefix("* ")
        || line.range(of: "^\\d+\\. ", options: .regularExpression) != nil
}

private func stripBullet(_ line: String) -> String {
    if line.hasPrefix("- ") || line.hasPrefix("* ") { return String(line.dropFirst(2)) }
    if let r = line.range(of: "^\\d+\\. ", options: .regularExpression) {
        return String(line[r.upperBound...])
    }
    return line
}

/// Inline spans via the system parser. `.inlineOnlyPreservingWhitespace`
/// keeps hard-wrapped agent text intact instead of collapsing it.
func inlineMarkdown(_ text: String) -> AttributedString {
    (try? AttributedString(
        markdown: text,
        options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    )) ?? AttributedString(text)
}

// MARK: - code highlighting

// Same deliberately small approach as the Android side: comments, strings,
// numbers and a shared keyword set across the languages Codex writes. No
// per-language grammars; unrecognised text stays body-coloured.
private let codeKeywords: Set<String> = [
    "func", "let", "var", "class", "struct", "enum", "protocol", "extension",
    "return", "if", "else", "guard", "switch", "case", "default", "for",
    "while", "repeat", "break", "continue", "in", "is", "as", "try", "catch",
    "throw", "throws", "import", "private", "public", "internal", "static",
    "override", "async", "await", "self", "super", "nil", "true", "false",
    "fun", "val", "object", "interface", "data", "when", "suspend", "const",
    "function", "new", "typeof", "export", "extends", "implements", "void",
    "null", "None", "True", "False", "def", "lambda", "pass", "raise", "with",
    "yield", "elif", "not", "and", "or", "impl", "trait", "pub", "mut", "fn",
    "use", "match", "type", "echo", "then", "fi", "esac", "local",
]

func highlightedCode(_ code: String) -> AttributedString {
    var out = AttributedString()
    var current = ""
    var iterator = code.startIndex

    func flushPlain() {
        guard !current.isEmpty else { return }
        out += AttributedString(current)
        current = ""
    }

    func emit(_ text: String, _ color: Color, italic: Bool = false) {
        flushPlain()
        var run = AttributedString(text)
        run.foregroundColor = color
        if italic { run.inlinePresentationIntent = .emphasized }
        out += run
    }

    while iterator < code.endIndex {
        let c = code[iterator]
        let rest = code[iterator...]

        // line comments: // # --
        if rest.hasPrefix("//") || c == "#" || rest.hasPrefix("--") {
            let end = rest.firstIndex(of: "\n") ?? code.endIndex
            emit(String(code[iterator..<end]), .remotexMuted, italic: true)
            iterator = end
            continue
        }
        // block comment
        if rest.hasPrefix("/*") {
            if let close = rest.range(of: "*/") {
                emit(String(code[iterator..<close.upperBound]), .remotexMuted, italic: true)
                iterator = close.upperBound
            } else {
                emit(String(rest), .remotexMuted, italic: true)
                iterator = code.endIndex
            }
            continue
        }
        // strings
        if c == "\"" || c == "'" || c == "`" {
            var j = code.index(after: iterator)
            while j < code.endIndex, code[j] != c {
                if code[j] == "\\", code.index(after: j) < code.endIndex {
                    j = code.index(after: j)
                }
                j = code.index(after: j)
            }
            let end = j < code.endIndex ? code.index(after: j) : code.endIndex
            emit(String(code[iterator..<end]), .remotexCodeString)
            iterator = end
            continue
        }
        // numbers
        if c.isNumber {
            var j = iterator
            while j < code.endIndex, code[j].isLetter || code[j].isNumber || code[j] == "." {
                j = code.index(after: j)
            }
            emit(String(code[iterator..<j]), .remotexCodeNumber)
            iterator = j
            continue
        }
        // identifiers / keywords
        if c.isLetter || c == "_" {
            var j = iterator
            while j < code.endIndex, code[j].isLetter || code[j].isNumber || code[j] == "_" {
                j = code.index(after: j)
            }
            let word = String(code[iterator..<j])
            if codeKeywords.contains(word) {
                emit(word, .remotexCodeKeyword)
            } else {
                current += word
            }
            iterator = j
            continue
        }

        current.append(c)
        iterator = code.index(after: iterator)
    }
    flushPlain()
    return out
}

// MARK: - views

struct MarkdownText: View {
    let text: String
    var color: Color = .remotexText
    var fontSize: CGFloat = 14

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(parseMarkdownBlocks(text)) { block in
                switch block {
                case let .paragraph(body):
                    Text(inlineMarkdown(body))
                        .font(.system(size: fontSize))
                        .foregroundStyle(color)
                        .textSelection(.enabled)
                case let .heading(level, body):
                    Text(inlineMarkdown(body))
                        .font(.system(size: fontSize + (level <= 2 ? 4 : 2), weight: .semibold))
                        .foregroundStyle(color)
                case let .bullets(items):
                    VStack(alignment: .leading, spacing: 3) {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                            HStack(alignment: .top, spacing: 6) {
                                Text("•").foregroundStyle(Color.remotexMuted)
                                Text(inlineMarkdown(item))
                                    .font(.system(size: fontSize))
                                    .foregroundStyle(color)
                            }
                        }
                    }
                case let .code(body):
                    CodeBlockView(code: body)
                }
            }
        }
    }
}

struct CodeBlockView: View {
    let code: String

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Text(highlightedCode(code))
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.remotexText)
                .textSelection(.enabled)
                .padding(8)
        }
        .background(Color.remotexSurface2)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.remotexLine, lineWidth: 1)
        )
    }
}

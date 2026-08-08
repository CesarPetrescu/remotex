import SwiftUI

// Renders the HEAD of each pending-prompt queue (contract F). Answering the
// head pops it and the next one takes its place, so a second concurrent
// prompt never hides an unanswered one — the header says how many wait.
struct PendingPromptsPanel: View {
    @ObservedObject var viewModel: RemotexViewModel

    var body: some View {
        if viewModel.hasPendingPrompts {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label(title, systemImage: "exclamationmark.bubble")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.remotexAccent)
                    Spacer()
                    Text("\(totalCount)")
                        .font(.caption.monospaced())
                        .foregroundStyle(Color.remotexMuted)
                }
                if queuedCount > 0 {
                    Text("\(queuedCount) more queued - answer this one to see the next")
                        .font(.caption)
                        .foregroundStyle(Color.remotexMuted)
                }
                if let approval = viewModel.pendingApprovals.first {
                    ApprovalPromptCard(prompt: approval) { decision in
                        viewModel.resolveApproval(decision)
                    }
                }
                if let request = viewModel.pendingUserInputs.first {
                    UserInputPromptCard(
                        prompt: request,
                        onSubmit: { answers in viewModel.resolveUserInput(answers) },
                        onCancel: { viewModel.cancelUserInput() }
                    )
                    // Fresh identity per prompt so the draft answers of the
                    // one just popped never leak into the next.
                    .id(request.callId)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.remotexSurface)
        }
    }

    private var title: String {
        totalCount == 1 ? "Pending prompt" : "Pending prompts"
    }

    private var totalCount: Int {
        viewModel.pendingApprovals.count + viewModel.pendingUserInputs.count
    }

    private var queuedCount: Int {
        max(0, viewModel.pendingApprovals.count - 1) + max(0, viewModel.pendingUserInputs.count - 1)
    }
}

private struct ApprovalPromptCard: View {
    let prompt: ApprovalPrompt
    let onDecision: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.remotexAccent)
            if let reason = prompt.reason, !reason.isEmpty {
                Text(reason)
                    .font(.callout)
                    .foregroundStyle(Color.remotexText)
                    .lineLimit(6)
            }
            if let command = prompt.command, !command.isEmpty {
                Text(command)
                    .font(.caption.monospaced())
                    .foregroundStyle(Color.remotexText)
                    .lineLimit(6)
                    .textSelection(.enabled)
            }
            if let cwd = prompt.cwd, !cwd.isEmpty {
                Text("cwd: \(cwd)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(Color.remotexMuted)
                    .lineLimit(1)
            }
            // Only the decisions the relay actually offered for this prompt.
            HStack(spacing: 8) {
                ForEach(prompt.decisions, id: \.self) { decision in
                    Button(label(for: decision)) {
                        onDecision(decision)
                    }
                    .font(.caption)
                    .buttonStyle(.bordered)
                    .tint(tint(for: decision))
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.remotexBackground)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.remotexAccent, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var title: String {
        switch prompt.kind {
        case "command":
            return "Command approval"
        case "permissions":
            return "Permission approval"
        default:
            return "File change approval"
        }
    }

    private func label(for decision: String) -> String {
        switch decision {
        case "acceptForSession":
            return "always"
        default:
            return decision
        }
    }

    private func tint(for decision: String) -> Color {
        switch decision {
        case "accept", "acceptForSession":
            return .remotexGreen
        case "decline", "cancel":
            return .remotexWarn
        default:
            return .remotexAccent
        }
    }
}

private struct UserInputPromptCard: View {
    let prompt: UserInputPrompt
    let onSubmit: ([String: [String]]) -> Void
    let onCancel: () -> Void

    @State private var selections: [String: String] = [:]
    @State private var notes: [String: String] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Codex asks")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.remotexBlue)
            if prompt.questions.isEmpty {
                Text("Codex is waiting for input.")
                    .font(.callout)
                    .foregroundStyle(Color.remotexText)
            }
            ForEach(prompt.questions) { question in
                questionView(question)
            }
            HStack(spacing: 8) {
                Button("skip") {
                    onCancel()
                }
                .font(.caption)
                .buttonStyle(.bordered)
                .tint(.remotexWarn)
                Spacer()
                Button("submit") {
                    onSubmit(wireAnswers())
                }
                .font(.caption)
                .buttonStyle(.bordered)
                .tint(.remotexGreen)
                .disabled(prompt.questions.isEmpty)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.remotexBackground)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.remotexBlue, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func questionView(_ question: UserInputQuestion) -> some View {
        let placeholder: String = question.options.isEmpty ? "type your answer" : "optional notes"
        return VStack(alignment: .leading, spacing: 6) {
            if !question.header.isEmpty {
                Text(question.header)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.remotexText)
            }
            if !question.question.isEmpty {
                Text(question.question)
                    .font(.callout)
                    .foregroundStyle(Color.remotexText)
            }
            ForEach(question.options) { option in
                Button {
                    selections[question.id] = option.label
                } label: {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: selection(for: question) == option.label
                              ? "largecircle.fill.circle"
                              : "circle")
                            .foregroundStyle(Color.remotexAccent)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.label)
                                .font(.callout)
                                .foregroundStyle(Color.remotexText)
                            if !option.description.isEmpty {
                                Text(option.description)
                                    .font(.caption)
                                    .foregroundStyle(Color.remotexMuted)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                }
                .buttonStyle(.plain)
            }
            TextField(
                placeholder,
                text: Binding(
                    get: { notes[question.id] ?? "" },
                    set: { notes[question.id] = $0 }
                ),
                axis: .vertical
            )
            .lineLimit(1...4)
            .textFieldStyle(.plain)
            .padding(8)
            .background(Color.remotexSurface)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(Color.remotexLine, lineWidth: 1)
            )
        }
    }

    private func selection(for question: UserInputQuestion) -> String? {
        selections[question.id] ?? question.options.first?.label
    }

    // { <question_id>: ["selected label", "freeform notes"] } — an empty
    // answer becomes ["skipped"], which is what codex expects for a
    // question the user passed on.
    private func wireAnswers() -> [String: [String]] {
        var out: [String: [String]] = [:]
        for question in prompt.questions {
            var values: [String] = []
            if let selected = selection(for: question), !selected.isEmpty {
                values.append(selected)
            }
            let note = (notes[question.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !note.isEmpty {
                values.append(note)
            }
            out[question.id] = values.isEmpty ? ["skipped"] : values
        }
        return out
    }
}

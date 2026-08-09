import SwiftUI

// Host telemetry, mirroring the web sidebar and the Android sheet: CPU /
// RAM / GPU / network cards with a live sparkline and now/peak/floor tiles.
struct TelemetryView: View {
    @ObservedObject var viewModel: RemotexViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let data = viewModel.telemetry {
                        if let cpu = data.cpu {
                            MetricCard(
                                title: "CPU",
                                value: "\(fmt(cpu.percent))%",
                                sub: [
                                    cpu.cores.map { "\($0) cores" },
                                    cpu.tempC.map { "\(fmt($0))°C package" },
                                ].compactMap { $0 }.joined(separator: " · "),
                                series: viewModel.telemetryCpu,
                                max: 100,
                                color: .remotexAccentDeep,
                                unit: "%"
                            )
                        }
                        if let mem = data.memory {
                            MetricCard(
                                title: "RAM",
                                value: "\(fmt(mem.percent))%",
                                sub: "\(gb(mem.usedBytes)) / \(gb(mem.totalBytes))",
                                series: viewModel.telemetryMem,
                                max: 100,
                                color: .remotexAccentDeep,
                                unit: "%"
                            )
                        }
                        let gpus = data.gpus ?? [data.gpu].compactMap { $0 }
                        ForEach(Array(gpus.enumerated()), id: \.offset) { index, gpu in
                            MetricCard(
                                title: gpus.count > 1 ? "GPU \(index + 1)" : "GPU",
                                value: "\(fmt(gpu.percent))%",
                                sub: [
                                    gpu.name,
                                    vram(gpu),
                                ].compactMap { $0 }.joined(separator: " · "),
                                // Only the first GPU has client-side history.
                                series: index == 0 ? viewModel.telemetryGpu : [],
                                max: 100,
                                color: .remotexGreen,
                                unit: "%"
                            )
                        }
                        if let net = data.network {
                            MetricCard(
                                title: "NETWORK",
                                value: "↑ \(bps(net.upBps))",
                                sub: "↓ \(bps(net.downBps)) · 3s transfer rate",
                                series: [],
                                max: nil,
                                color: .remotexAccent,
                                unit: ""
                            )
                        }
                        HStack(spacing: 18) {
                            Fact(label: "UPTIME", value: uptime(data.uptimeS))
                            Fact(
                                label: "LOAD AVG",
                                value: data.loadAvg?.prefix(3).map { fmt($0) }.joined(separator: " ") ?? "—"
                            )
                        }
                        .padding(.top, 4)
                    } else {
                        Text("waiting for the host to report…")
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Color.remotexMuted)
                            .padding(.vertical, 24)
                    }
                }
                .padding(12)
            }
            .background(Color.remotexBackground)
            .navigationTitle("Telemetry")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { viewModel.startTelemetry() }
    }
}

private struct MetricCard: View {
    let title: String
    let value: String
    let sub: String
    let series: [Double]
    let max: Double?
    let color: Color
    let unit: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(Color.remotexMuted)
            Text(value)
                .font(.system(size: 26, weight: .semibold, design: .monospaced))
                .foregroundStyle(Color.remotexText)
            if !sub.isEmpty {
                Text(sub)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Color.remotexMuted)
                    .lineLimit(1)
            }
            if series.count > 1 {
                Sparkline(series: series, max: max, color: color)
                    .frame(height: 56)
                HStack(spacing: 8) {
                    Tile(label: "NOW", value: "\(fmt(series.last))\(unit)")
                    Tile(label: "PEAK", value: "\(fmt(series.max()))\(unit)")
                    Tile(label: "FLOOR", value: "\(fmt(series.min()))\(unit)")
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.remotexSurface)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// Same autoscale rule as the web/Android sparklines: the axis zooms to
/// ~1.3× the observed peak (floored at 8% of full scale) so a 3% CPU line
/// has shape instead of hugging the bottom of a fixed 0-100 plot.
private struct Sparkline: View {
    let series: [Double]
    let max: Double?
    let color: Color

    var body: some View {
        GeometryReader { geo in
            // ponytail: points precomputed outside the ViewBuilder — a local
            // func with `return` isn't allowed inside a result builder body.
            let pts = points(in: geo.size)
            let h = geo.size.height
            let w = geo.size.width
            ZStack {
                ForEach(1..<4, id: \.self) { i in
                    Path { p in
                        let y = h * CGFloat(i) / 4
                        p.move(to: CGPoint(x: 0, y: y))
                        p.addLine(to: CGPoint(x: w, y: y))
                    }
                    .stroke(Color.remotexLine, lineWidth: 1)
                }
                Path { p in
                    p.move(to: CGPoint(x: 0, y: h))
                    for pt in pts { p.addLine(to: pt) }
                    p.addLine(to: CGPoint(x: w, y: h))
                    p.closeSubpath()
                }
                .fill(color.opacity(0.16))
                Path { p in
                    for (i, pt) in pts.enumerated() {
                        if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                    }
                }
                .stroke(color, lineWidth: 2)
                if let last = pts.last {
                    Circle()
                        .fill(color)
                        .frame(width: 7, height: 7)
                        .position(last)
                }
            }
        }
    }

    private func points(in size: CGSize) -> [CGPoint] {
        guard !series.isEmpty else { return [] }
        let dataMax = series.max() ?? 1
        let cap: Double
        if let max {
            cap = Swift.max(Swift.min(max, dataMax * 1.3), max * 0.08, 0.001)
        } else {
            cap = Swift.max(dataMax * 1.15, 1)
        }
        let padY: CGFloat = 4
        let stepX = series.count > 1 ? size.width / CGFloat(series.count - 1) : size.width
        return series.indices.map { i in
            let v = Swift.min(Swift.max(series[i], 0), cap)
            let y = size.height - padY - CGFloat(v / cap) * (size.height - padY * 2)
            return CGPoint(x: stepX * CGFloat(i), y: y)
        }
    }
}

private struct Tile: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label)
                .font(.system(size: 9, design: .monospaced))
                .foregroundStyle(Color.remotexMuted)
            Text(value)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.remotexText)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.remotexSurface2)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

private struct Fact: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label)
                .font(.system(size: 9, design: .monospaced))
                .foregroundStyle(Color.remotexMuted)
            Text(value)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.remotexText)
        }
    }
}

private func fmt(_ value: Double?) -> String {
    guard let value else { return "—" }
    return value >= 10 ? String(Int(value.rounded())) : String(format: "%.1f", value)
}

private func gb(_ bytes: Int64?) -> String {
    guard let bytes else { return "—" }
    return String(format: "%.1f GB", Double(bytes) / 1_073_741_824)
}

private func vram(_ gpu: GpuTelemetry) -> String? {
    guard let used = gpu.memUsedMb, let total = gpu.memTotalMb else { return nil }
    return "\(Int((used / 1024).rounded())) GB / \(Int((total / 1024).rounded())) GB VRAM"
}

private func bps(_ value: Int64?) -> String {
    let b = Double(value ?? 0)
    if b >= 1_000_000 { return String(format: "%.1f Mbps", b / 1_000_000) }
    if b >= 1_000 { return String(format: "%.0f kbps", b / 1_000) }
    return "\(Int(b)) bps"
}

private func uptime(_ seconds: Int64?) -> String {
    guard let seconds else { return "—" }
    let days = seconds / 86_400
    let hours = (seconds % 86_400) / 3_600
    return days > 0 ? "\(days)d \(hours)h" : "\(hours)h"
}

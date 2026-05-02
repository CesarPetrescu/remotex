"""Periodic host-level telemetry collection for the daemon.

Runs as a background task alongside the main relay WS loop and emits
`host-telemetry` frames every N seconds. Uses psutil when available (the
cross-platform path); falls back to /proc readers on Linux. GPU numbers
come from `nvidia-smi` if the binary is on PATH, else omitted.
"""
from __future__ import annotations

import asyncio
import logging
import os
import shutil
import subprocess
import time
from typing import Any, Awaitable, Callable

try:  # psutil is optional — collector degrades gracefully without it
    import psutil  # type: ignore

    HAS_PSUTIL = True
except ImportError:  # pragma: no cover - fallback path
    HAS_PSUTIL = False

log = logging.getLogger("daemon.telemetry")

_DEFAULT_INTERVAL_S = 3.0


class TelemetryCollector:
    """Samples CPU/RAM/GPU/network/uptime/load/temp; stateful for rate calcs."""

    def __init__(self) -> None:
        self._boot_time = self._read_boot_time()
        self._last_net: tuple[int, int, float] | None = None  # (sent, recv, ts)
        self._gpu_available: bool | None = None
        self._gpu_probe_at = 0.0

    def _read_boot_time(self) -> float:
        if HAS_PSUTIL:
            try:
                return float(psutil.boot_time())
            except Exception:  # noqa: BLE001
                pass
        try:
            with open("/proc/stat") as fh:
                for line in fh:
                    if line.startswith("btime"):
                        return float(line.split()[1])
        except OSError:
            pass
        return time.time()

    async def sample(self) -> dict[str, Any]:
        """Take one snapshot. Offloads blocking I/O to a worker thread."""
        return await asyncio.to_thread(self._sample_sync)

    def _sample_sync(self) -> dict[str, Any]:
        now = time.time()
        uptime_s = max(0, int(now - self._boot_time))
        try:
            load_avg = list(os.getloadavg())
        except OSError:
            load_avg = [0.0, 0.0, 0.0]

        if HAS_PSUTIL:
            cpu = self._cpu_psutil()
            memory = self._mem_psutil()
            network = self._net_psutil(now)
        else:
            cpu = self._cpu_proc()
            memory = self._mem_proc()
            network = self._net_proc(now)

        gpus = self._gpus()
        return {
            "cpu": cpu,
            "memory": memory,
            # New shape: list of all GPUs (empty if none). Keep `gpu` as
            # the first item so older clients that read data.gpu keep
            # working until they migrate to `gpus`.
            "gpus": gpus,
            "gpu": gpus[0] if gpus else None,
            "network": network,
            "uptime_s": uptime_s,
            "load_avg": load_avg,
            "ts": now,
        }

    # --- psutil path ---

    def _cpu_psutil(self) -> dict[str, Any]:
        percent = psutil.cpu_percent(interval=None)
        cores = psutil.cpu_count(logical=True) or 1
        temp = None
        if hasattr(psutil, "sensors_temperatures"):
            try:
                temps = psutil.sensors_temperatures() or {}
                for key in ("coretemp", "k10temp", "cpu_thermal", "cpu-thermal", "zenpower"):
                    if key in temps and temps[key]:
                        temp = float(temps[key][0].current)
                        break
                if temp is None:
                    for sensors in temps.values():
                        if sensors:
                            temp = float(sensors[0].current)
                            break
            except Exception:  # noqa: BLE001
                temp = None
        return {"percent": round(float(percent), 1), "cores": int(cores), "temp_c": temp}

    def _mem_psutil(self) -> dict[str, Any]:
        vm = psutil.virtual_memory()
        return {
            "used_bytes": int(vm.used),
            "total_bytes": int(vm.total),
            "percent": round(float(vm.percent), 1),
        }

    def _net_psutil(self, now: float) -> dict[str, Any]:
        try:
            counters = psutil.net_io_counters()
        except Exception:  # noqa: BLE001
            return {"up_bps": 0, "down_bps": 0}
        return self._net_rate(int(counters.bytes_sent), int(counters.bytes_recv), now)

    # --- /proc fallback ---

    def _cpu_proc(self) -> dict[str, Any]:
        try:
            with open("/proc/stat") as fh:
                first = fh.readline().split()
            total = sum(int(x) for x in first[1:])
            idle = int(first[4])
            prev_total, prev_idle = getattr(self, "_cpu_prev", (0, 0))
            self._cpu_prev = (total, idle)
            dt = total - prev_total
            di = idle - prev_idle
            percent = 0.0 if dt <= 0 else (1.0 - di / dt) * 100.0
        except OSError:
            percent = 0.0
        try:
            cores = os.cpu_count() or 1
        except Exception:  # noqa: BLE001
            cores = 1
        temp = self._cpu_temp_proc()
        return {"percent": round(percent, 1), "cores": int(cores), "temp_c": temp}

    def _cpu_temp_proc(self) -> float | None:
        base = "/sys/class/thermal"
        if not os.path.isdir(base):
            return None
        try:
            for name in sorted(os.listdir(base)):
                if not name.startswith("thermal_zone"):
                    continue
                try:
                    with open(os.path.join(base, name, "temp")) as fh:
                        return int(fh.read().strip()) / 1000.0
                except OSError:
                    continue
        except OSError:
            pass
        return None

    def _mem_proc(self) -> dict[str, Any]:
        info: dict[str, int] = {}
        try:
            with open("/proc/meminfo") as fh:
                for line in fh:
                    k, _, rest = line.partition(":")
                    info[k.strip()] = int(rest.strip().split()[0]) * 1024  # kB → B
        except OSError:
            return {"used_bytes": 0, "total_bytes": 0, "percent": 0.0}
        total = info.get("MemTotal", 0)
        available = info.get("MemAvailable", info.get("MemFree", 0))
        used = max(0, total - available)
        percent = 0.0 if total == 0 else used / total * 100.0
        return {"used_bytes": used, "total_bytes": total, "percent": round(percent, 1)}

    def _net_proc(self, now: float) -> dict[str, Any]:
        sent = recv = 0
        try:
            with open("/proc/net/dev") as fh:
                for line in fh.readlines()[2:]:
                    name, _, stats = line.partition(":")
                    if name.strip() in ("lo",) or not stats.strip():
                        continue
                    parts = stats.split()
                    recv += int(parts[0])
                    sent += int(parts[8])
        except OSError:
            return {"up_bps": 0, "down_bps": 0}
        return self._net_rate(sent, recv, now)

    def _net_rate(self, sent: int, recv: int, now: float) -> dict[str, Any]:
        if self._last_net is None:
            self._last_net = (sent, recv, now)
            return {"up_bps": 0, "down_bps": 0}
        prev_sent, prev_recv, prev_ts = self._last_net
        dt = max(1e-6, now - prev_ts)
        up_bps = max(0, (sent - prev_sent) * 8 / dt)
        down_bps = max(0, (recv - prev_recv) * 8 / dt)
        self._last_net = (sent, recv, now)
        return {"up_bps": int(up_bps), "down_bps": int(down_bps)}

    # --- GPU via nvidia-smi ---

    def _gpus(self) -> list[dict[str, Any]]:
        """Return one entry per GPU. Empty list if nvidia-smi missing/fails."""
        # Cache the "no nvidia-smi" answer so we don't PATH-lookup every 3s.
        # Re-probe once every 5 minutes in case the user installs drivers.
        now = time.time()
        if self._gpu_available is False and (now - self._gpu_probe_at) < 300:
            return []
        binary = shutil.which("nvidia-smi")
        self._gpu_probe_at = now
        if not binary:
            self._gpu_available = False
            return []
        try:
            out = subprocess.check_output(
                [
                    binary,
                    "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                    "--format=csv,noheader,nounits",
                ],
                timeout=2.0,
                text=True,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            log.debug("nvidia-smi failed: %s", exc)
            self._gpu_available = False
            return []
        self._gpu_available = True
        gpus: list[dict[str, Any]] = []
        for raw in out.splitlines():
            line = raw.strip()
            if not line:
                continue
            parts = [p.strip() for p in line.split(",")]

            # Default-arg captures `parts` per-iteration so a future caller
            # reusing _f outside the loop wouldn't see the wrong row's
            # values (ruff B023 — also makes the intent explicit).
            def _f(i: int, parts: list[str] = parts) -> float | None:
                if i >= len(parts) or not parts[i]:
                    return None
                try:
                    return float(parts[i])
                except ValueError:
                    return None

            gpus.append({
                "name": parts[0] if parts and parts[0] else None,
                "percent": _f(1),
                "mem_used_mb": _f(2),
                "mem_total_mb": _f(3),
                "temp_c": _f(4),
            })
        return gpus


async def telemetry_loop(
    collector: TelemetryCollector,
    send: Callable[[dict[str, Any]], Awaitable[None]],
    *,
    interval_s: float = _DEFAULT_INTERVAL_S,
) -> None:
    """Emits `host-telemetry` frames until cancelled."""
    # Prime psutil's CPU percent counter so the first real sample isn't 0.0.
    if HAS_PSUTIL:
        try:
            psutil.cpu_percent(interval=None)
        except Exception:  # noqa: BLE001
            pass
    try:
        while True:
            try:
                data = await collector.sample()
                await send({"type": "host-telemetry", "data": data})
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                log.debug("telemetry sample failed: %s", exc)
            await asyncio.sleep(interval_s)
    except asyncio.CancelledError:
        return

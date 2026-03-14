import type { CurrentChildPreset, HudSeries, HudSnapshot, LiveStage } from "./app-types";

export function parseHud(lines: string[]): HudSnapshot {
  let trialId = "-";
  let score = "-";
  let presentFps = "-";
  let recvFps = "-";
  let bitrateMbps = "-";
  let e2eP95Ms = "-";
  let dropsPerSec = "-";
  let progress = "-";
  let generation = "-";
  let mode = "-";

  const trialStartRe = /^\[(t\d+)] apply /;
  const trialScoreRe =
    /^\[(t\d+)] score=([-0-9.]+) present=([-0-9.]+) recv=([-0-9.]+) e2e95=([-0-9.]+)ms drops\/s=([-0-9.]+)(?: mbps=([-0-9.]+))?/;
  const progressRe = /^trial space=(\d+) running=(\d+)/;
  const protoTrialRe = /\btrial=([A-Za-z0-9_.:-]+)/;
  const protoDoneRe =
    /done trial=([A-Za-z0-9_.:-]+) score=([-0-9.]+).*sender_p50=([-0-9.]+).*pipe_p50=([-0-9.]+).*timeout_mean=([-0-9.]+).*drop=([-0-9.]+)%(?:.*mbps=([-0-9.]+))?/;
  const protoGenRe = /generation\s+(\d+)\/(\d+):\s+population=(\d+)\s+\(start\)/;
  const modeRe = /"mode"\s*:\s*"([^"]+)"/;

  for (const line of lines) {
    let start = trialStartRe.exec(line);
    if (start) trialId = start[1];

    let scoreMatch = trialScoreRe.exec(line);
    if (scoreMatch) {
      trialId = scoreMatch[1];
      score = scoreMatch[2];
      presentFps = scoreMatch[3];
      recvFps = scoreMatch[4];
      e2eP95Ms = scoreMatch[5];
      dropsPerSec = scoreMatch[6];
      if (scoreMatch[7]) bitrateMbps = scoreMatch[7];
    }

    let p = progressRe.exec(line);
    if (p) progress = `${p[2]}/${p[1]}`;

    let protoTrial = protoTrialRe.exec(line);
    if (protoTrial) trialId = protoTrial[1];

    let protoDone = protoDoneRe.exec(line);
    if (protoDone) {
      trialId = protoDone[1];
      score = protoDone[2];
      presentFps = protoDone[3];
      recvFps = protoDone[4];
      e2eP95Ms = protoDone[5];
      dropsPerSec = `${protoDone[6]}%`;
      if (protoDone[7]) bitrateMbps = protoDone[7];
    }

    let protoGen = protoGenRe.exec(line);
    if (protoGen) {
      generation = `${protoGen[1]}/${protoGen[2]}`;
      progress = `gen ${protoGen[1]}/${protoGen[2]} pop ${protoGen[3]}`;
    }

    let parsedMode = modeRe.exec(line);
    if (parsedMode) {
      mode = parsedMode[1];
    }
  }

  return { trialId, score, presentFps, recvFps, bitrateMbps, e2eP95Ms, dropsPerSec, progress, generation, mode };
}

export function parseHudSeries(lines: string[]): HudSeries {
  const trialScoreRe =
    /^\[(t\d+)] score=([-0-9.]+) present=([-0-9.]+) recv=([-0-9.]+) e2e95=([-0-9.]+)ms drops\/s=([-0-9.]+)/;
  const protoDoneRe =
    /done trial=([A-Za-z0-9_.:-]+) score=([-0-9.]+).*sender_p50=([-0-9.]+).*pipe_p50=([-0-9.]+).*timeout_mean=([-0-9.]+).*drop=([-0-9.]+)%/;
  const series: HudSeries = { score: [], present: [], recv: [], drops: [] };
  for (const line of lines) {
    let match = trialScoreRe.exec(line);
    if (match) {
      series.score.push(Number(match[2]));
      series.present.push(Number(match[3]));
      series.recv.push(Number(match[4]));
      series.drops.push(Number(match[6]));
      continue;
    }
    let protoDone = protoDoneRe.exec(line);
    if (protoDone) {
      series.score.push(Number(protoDone[2]));
      series.present.push(Number(protoDone[3]));
      series.recv.push(Number(protoDone[4]));
      series.drops.push(Number(protoDone[6]));
    }
  }
  const keep = 22;
  return {
    score: series.score.slice(-keep),
    present: series.present.slice(-keep),
    recv: series.recv.slice(-keep),
    drops: series.drops.slice(-keep),
  };
}

export function parseLiveStages(lines: string[]): LiveStage[] {
  const output: LiveStage[] = [];
  for (const line of lines) {
    const ts = line.slice(0, 8);
    if (line.includes("generation ") && line.includes("population=")) {
      output.push({ label: "Generation", detail: line.trim(), ts, level: "info" });
      continue;
    }
    if (line.includes("warmup")) {
      output.push({ label: "Warmup", detail: line.trim(), ts, level: "info" });
      continue;
    }
    if (line.includes("sampling")) {
      output.push({ label: "Sampling", detail: line.trim(), ts, level: "info" });
      continue;
    }
    if (line.includes("done trial=") || line.includes(" score=")) {
      output.push({ label: "Trial complete", detail: line.trim(), ts, level: "ok" });
      continue;
    }
    if (line.includes("note: health gate")) {
      output.push({ label: "Health gate", detail: line.trim(), ts, level: "warn" });
      continue;
    }
    if (line.includes("ERROR") || line.includes("failed")) {
      output.push({ label: "Failure", detail: line.trim(), ts, level: "risk" });
    }
  }
  return output.slice(-10).reverse();
}

export function inferLiveHealth(lines: string[]): { state: string; tone: "ok" | "warn" | "risk" | "info" } {
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    const line = lines[i];
    if (line.includes("ERROR") || line.includes("failed")) {
      return { state: "degraded", tone: "risk" };
    }
    if (line.includes("note: health gate")) {
      return { state: "gated", tone: "warn" };
    }
    if (line.includes("sampling") || line.includes("warmup") || line.includes("trial=")) {
      return { state: "active", tone: "ok" };
    }
  }
  return { state: "idle", tone: "info" };
}

export function parseCurrentChildPreset(lines: string[]): CurrentChildPreset | null {
  const applyRe = /^\[(t\d+)\]\s+apply\s+encoder=([^\s]+)\s+size=([^\s]+)\s+fps=(\d+)\s+bitrate=(\d+)/;
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    const line = lines[i];
    const match = applyRe.exec(line);
    if (!match) continue;
    return {
      trialId: match[1],
      encoder: match[2],
      size: match[3],
      fps: match[4],
      bitrateKbps: match[5],
    };
  }
  return null;
}

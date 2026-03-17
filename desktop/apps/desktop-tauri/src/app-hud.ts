import type { CurrentChildPreset, HudSeries, HudSnapshot, LiveStage } from "./app-types";

function updateHudFromTrialStart(line: string, hudData: Record<string, string>): void {
  const trialStartRe = /^\[(t\d+)] apply /;
  const start = trialStartRe.exec(line);
  if (start) hudData.trialId = start[1];
}

function updateHudFromTrialScore(
  line: string,
  hudData: Record<string, string>
): void {
  const trialScoreRe =
    /^\[(t\d+)] score=([-0-9.]+) present=([-0-9.]+) recv=([-0-9.]+) e2e95=([-0-9.]+)ms drops\/s=([-0-9.]+)(?: mbps=([-0-9.]+))?/;
  const scoreMatch = trialScoreRe.exec(line);
  if (scoreMatch) {
    hudData.trialId = scoreMatch[1];
    hudData.score = scoreMatch[2];
    hudData.presentFps = scoreMatch[3];
    hudData.recvFps = scoreMatch[4];
    hudData.e2eP95Ms = scoreMatch[5];
    hudData.dropsPerSec = scoreMatch[6];
    if (scoreMatch[7]) hudData.bitrateMbps = scoreMatch[7];
  }
}

function updateHudFromProgress(line: string, hudData: Record<string, string>): void {
  const progressRe = /^trial space=(\d+) running=(\d+)/;
  const p = progressRe.exec(line);
  if (p) hudData.progress = `${p[2]}/${p[1]}`;
}

function updateHudFromProtoTrial(line: string, hudData: Record<string, string>): void {
  const protoTrialRe = /\btrial=([\w.:-]+)/;
  const protoTrial = protoTrialRe.exec(line);
  if (protoTrial) hudData.trialId = protoTrial[1];
}

function updateHudFromProtoDone(
  line: string,
  hudData: Record<string, string>
): void {
  const field = (key: string): string | undefined =>
    line
      .split(/\s+/)
      .find((part) => part.startsWith(`${key}=`))
      ?.slice(key.length + 1);
  const trialMatch = /done trial=([\w.:-]+)/.exec(line);
  if (trialMatch) {
    hudData.trialId = trialMatch[1];
    hudData.score = field("score") ?? hudData.score;
    hudData.presentFps = field("sender_p50") ?? hudData.presentFps;
    hudData.recvFps = field("pipe_p50") ?? hudData.recvFps;
    hudData.e2eP95Ms = field("timeout_mean") ?? hudData.e2eP95Ms;
    const drop = field("drop");
    if (drop) hudData.dropsPerSec = `${drop}%`;
    const mbps = field("mbps");
    if (mbps) hudData.bitrateMbps = mbps;
  }
}

function updateHudFromProtoGen(line: string, hudData: Record<string, string>): void {
  const protoGenRe = /generation\s+(\d+)\/(\d+):\s+population=(\d+)\s+\(start\)/;
  const protoGen = protoGenRe.exec(line);
  if (protoGen) {
    hudData.generation = `${protoGen[1]}/${protoGen[2]}`;
    hudData.progress = `gen ${protoGen[1]}/${protoGen[2]} pop ${protoGen[3]}`;
  }
}

function updateHudFromMode(line: string, hudData: Record<string, string>): void {
  const modeRe = /"mode"\s*:\s*"([^"]+)"/;
  const parsedMode = modeRe.exec(line);
  if (parsedMode) {
    hudData.mode = parsedMode[1];
  }
}

export function parseHud(lines: string[]): HudSnapshot {
  const hudData: Record<string, string> = {
    trialId: "-",
    score: "-",
    presentFps: "-",
    recvFps: "-",
    bitrateMbps: "-",
    e2eP95Ms: "-",
    dropsPerSec: "-",
    progress: "-",
    generation: "-",
    mode: "-",
  };

  for (const line of lines) {
    updateHudFromTrialStart(line, hudData);
    updateHudFromTrialScore(line, hudData);
    updateHudFromProgress(line, hudData);
    updateHudFromProtoTrial(line, hudData);
    updateHudFromProtoDone(line, hudData);
    updateHudFromProtoGen(line, hudData);
    updateHudFromMode(line, hudData);
  }

  return {
    trialId: hudData.trialId,
    score: hudData.score,
    presentFps: hudData.presentFps,
    recvFps: hudData.recvFps,
    bitrateMbps: hudData.bitrateMbps,
    e2eP95Ms: hudData.e2eP95Ms,
    dropsPerSec: hudData.dropsPerSec,
    progress: hudData.progress,
    generation: hudData.generation,
    mode: hudData.mode,
  };
}

export function parseHudSeries(lines: string[]): HudSeries {
  const trialScoreRe =
    /^\[(t\d+)] score=([-0-9.]+) present=([-0-9.]+) recv=([-0-9.]+) e2e95=([-0-9.]+)ms drops\/s=([-0-9.]+)/;
  const protoDoneRe =
    /done trial=([\w.:-]+) score=([-0-9.]+).*sender_p50=([-0-9.]+).*pipe_p50=([-0-9.]+).*timeout_mean=([-0-9.]+).*drop=([-0-9.]+)%/;
  const series: HudSeries = { score: [], present: [], recv: [], drops: [] };
  for (const line of lines) {
    const match = trialScoreRe.exec(line);
    if (match) {
      series.score.push(Number(match[2]));
      series.present.push(Number(match[3]));
      series.recv.push(Number(match[4]));
      series.drops.push(Number(match[6]));
      continue;
    }
    const protoDone = protoDoneRe.exec(line);
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

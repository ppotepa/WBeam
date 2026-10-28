import Download from "lucide-solid/icons/download";
import Trash2 from "lucide-solid/icons/trash-2";
import Play from "lucide-solid/icons/play";
import Square from "lucide-solid/icons/square";
import type { ServiceStatus } from "../types";

export function ServiceControls(props: {
  service: ServiceStatus;
  busy: boolean;
  upgradeAvailable: boolean;
  onInstall: () => Promise<unknown> | void;
  onUninstall: () => Promise<unknown> | void;
  onStart: () => Promise<unknown> | void;
  onStop: () => Promise<unknown> | void;
}) {
  return (
    <section class="service-controls">
      <button
        class={`svc-btn ${props.upgradeAvailable ? "svc-upgrade" : ""}`}
        disabled={props.busy || !props.service.available || (!props.upgradeAvailable && props.service.installed)}
        onClick={() => void props.onInstall()}
        title={props.upgradeAvailable ? "Upgrade host service" : "Install user service"}
      >
        <Download size={14} />
        <span>{props.upgradeAvailable ? "Upgrade" : "Install service"}</span>
      </button>
      <button
        class="svc-btn"
        disabled={props.busy || !props.service.available || !props.service.installed}
        onClick={() => void props.onUninstall()}
        title="Uninstall user service"
      >
        <Trash2 size={14} />
        <span>Uninstall</span>
      </button>
      <button
        class="svc-btn"
        disabled={props.busy || !props.service.available || !props.service.installed || props.service.active}
        onClick={() => void props.onStart()}
        title="Start service"
      >
        <Play size={14} />
        <span>Start</span>
      </button>
      <button
        class="svc-btn"
        disabled={props.busy || !props.service.available || !props.service.installed || !props.service.active}
        onClick={() => void props.onStop()}
        title="Stop service"
      >
        <Square size={14} />
        <span>Stop</span>
      </button>
    </section>
  );
}

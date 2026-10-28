export type RefreshIntent = { silent: boolean; forceDevices: boolean };

// Serialize status reads and merge higher-priority requests without losing
// a manual refresh behind an in-flight background poll.
export function createRefreshCoordinator(
  work: (request: RefreshIntent) => Promise<void>,
  onState: (busy: boolean, interactive: boolean) => void,
) {
  let active: Promise<void> | null = null;
  let running: RefreshIntent | null = null;
  let pending: RefreshIntent | null = null;
  let interactive = false;

  function refresh(request: RefreshIntent): Promise<void> {
    if (active) {
      // User-initiated refresh always gets a new pass. Focus/visibility events
      // coalesce unless the active pass already forces the device snapshot.
      if (!request.silent || (request.forceDevices && !running?.forceDevices)) {
        pending = pending
          ? { silent: pending.silent && request.silent, forceDevices: pending.forceDevices || request.forceDevices }
          : request;
      }
      if (!request.silent && !interactive) {
        interactive = true;
        onState(true, true);
      }
      return active;
    }

    interactive = !request.silent;
    onState(true, interactive);
    active = (async () => {
      let next: RefreshIntent | null = request;
      try {
        while (next) {
          running = next;
          await work(next);
          next = pending;
          pending = null;
        }
      } finally {
        active = null;
        running = null;
        pending = null;
        interactive = false;
        onState(false, false);
      }
    })();
    return active;
  }

  return { refresh };
}

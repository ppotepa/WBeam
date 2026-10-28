type TimerHandle = ReturnType<typeof setTimeout>;

export type PollClock = {
  schedule: (callback: () => void, delayMs: number) => TimerHandle;
  cancel: (handle: TimerHandle) => void;
};

const defaultClock: PollClock = {
  schedule: (callback, delayMs) => setTimeout(callback, delayMs),
  cancel: (handle) => clearTimeout(handle),
};

export function createPollingLoop(options: {
  delayMs: number;
  shouldPoll: () => boolean;
  work: () => Promise<unknown>;
  onError: (error: unknown) => void;
  clock?: PollClock;
}) {
  const clock = options.clock ?? defaultClock;
  let started = false;
  let inFlight = false;
  let timer: TimerHandle | null = null;

  function schedule(): void {
    if (!started || inFlight || timer !== null) return;
    timer = clock.schedule(() => {
      timer = null;
      void tick();
    }, options.delayMs);
  }

  async function tick(): Promise<void> {
    if (!started || inFlight) return;
    if (!options.shouldPoll()) {
      schedule();
      return;
    }
    inFlight = true;
    try {
      await options.work();
    } catch (error) {
      options.onError(error);
    } finally {
      inFlight = false;
      schedule();
    }
  }

  return {
    start(): void {
      if (started) return;
      started = true;
      schedule();
    },
    stop(): void {
      started = false;
      if (timer !== null) clock.cancel(timer);
      timer = null;
    },
  };
}

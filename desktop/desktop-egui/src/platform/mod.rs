mod linux;
mod resolver;
mod shared_checks;
pub(crate) mod traits;
mod windows;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::sync::Arc;
use std::thread;
use std::thread::JoinHandle;

use traits::{BootstrapState, MonitorEvent, PlatformModule};

pub(crate) use resolver::resolve_platform_module;

pub(crate) struct BackgroundMonitor {
    rx: Receiver<MonitorEvent>,
    stop: Arc<AtomicBool>,
    handles: Vec<JoinHandle<()>>,
}

impl BackgroundMonitor {
    pub(crate) fn try_recv(&self) -> Result<MonitorEvent, TryRecvError> {
        self.rx.try_recv()
    }
}

impl Drop for BackgroundMonitor {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        for handle in self.handles.drain(..) {
            let _ = handle.join();
        }
    }
}

pub(crate) fn run_startup_checks(
    module: &dyn PlatformModule,
) -> (BootstrapState, Vec<MonitorEvent>) {
    let mut state = BootstrapState::default();
    let mut events = Vec::new();

    for check in module.startup_checks() {
        match check.run(&mut state) {
            Ok(Some(event)) => events.push(event),
            Ok(None) => {}
            Err(err) => events.push(MonitorEvent::Error {
                source: check.id(),
                message: format!("startup check failed: {err}"),
            }),
        }
    }

    (state, events)
}

pub(crate) fn start_background_checks(module: &dyn PlatformModule) -> BackgroundMonitor {
    let (tx, rx) = mpsc::channel::<MonitorEvent>();
    let stop = Arc::new(AtomicBool::new(false));
    let mut handles = Vec::new();

    for mut check in module.background_checks() {
        let tx = tx.clone();
        let stop = Arc::clone(&stop);
        handles.push(thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                match check.tick() {
                    Ok(Some(event)) => {
                        if tx.send(event).is_err() {
                            break;
                        }
                    }
                    Ok(None) => {}
                    Err(err) => {
                        if tx
                            .send(MonitorEvent::Warn {
                                source: check.id(),
                                message: format!("background check failed: {err}"),
                            })
                            .is_err()
                        {
                            break;
                        }
                    }
                }
                thread::sleep(check.interval());
            }
        }));
    }

    BackgroundMonitor { rx, stop, handles }
}

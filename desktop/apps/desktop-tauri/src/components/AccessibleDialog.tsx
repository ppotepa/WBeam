import { onCleanup, onMount, type JSX } from "solid-js";

export function AccessibleDialog(props: {
  label: string;
  onClose: () => void;
  children: JSX.Element;
}) {
  let dialog!: HTMLDialogElement;
  let previousFocus: HTMLElement | null = null;

  function onCancel(event: Event): void {
    // Let the controlling Solid signal close/unmount the dialog. A blocked
    // destructive install can reject Esc via its onClose handler.
    event.preventDefault();
    props.onClose();
  }

  onMount(() => {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog.addEventListener("cancel", onCancel);
    if (!dialog.open) dialog.showModal();
    const initial = dialog.querySelector<HTMLElement>("button:not(:disabled), select:not(:disabled), input:not(:disabled)");
    initial?.focus();
  });

  onCleanup(() => {
    dialog.removeEventListener("cancel", onCancel);
    if (dialog.open) dialog.close();
    previousFocus?.focus();
  });

  return (
    <dialog ref={dialog} class="modal-backdrop" aria-label={props.label}>
      {props.children}
    </dialog>
  );
}

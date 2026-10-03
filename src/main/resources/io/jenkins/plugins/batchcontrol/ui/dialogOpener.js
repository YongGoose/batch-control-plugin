/*
 * D-66: a classic sidebar entry (l:task with data-callback="batchControlOpenDialog") opens its
 * request form in core's dialog, the one data-type="dialog-opener" with data-dialog-url uses.
 * l:task renders a link, and core's dialog-opener behaviour does not stop the link from being
 * followed, so the sidebar entry calls this instead. The link's own href (the full request page)
 * stays for a middle click, and is followed if core's dialog is not available.
 */
window.batchControlOpenDialog = function (element, event) {
  if (!window.dialog || typeof window.dialog.wizard !== "function" || !element.dataset.dialogUrl) {
    return;
  }
  event.preventDefault();
  window.dialog.wizard(element.dataset.dialogUrl, {
    minWidth: "min(550px, 100vw)",
    preventCloseOnOutsideClick: true,
  });
};

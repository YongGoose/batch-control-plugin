/*
 * e2e-12 DEF-07: Jenkins core's hudson-behavior.js appends a hidden crumb field (and, unless the
 * form has the class "no-json", a hidden "json" field) to every <form> on the page. On a GET form
 * those fields end up in the address bar, the browser history, bookmarks, proxy logs and the
 * Referer of the next request. The read-only filter forms of this plugin carry the attribute
 * data-batch-control-get-form and the class "no-json"; just before such a form is submitted this
 * drops the core-added fields, so the URL holds only the filter parameters. Without JavaScript
 * core adds nothing, so the form needs nothing either. Filtering never changes state, so it never
 * needs a crumb.
 */
document.addEventListener(
  "submit",
  function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.hasAttribute("data-batch-control-get-form")) {
      return;
    }
    var names = ["json", "Jenkins-Crumb"];
    if (window.crumb && typeof window.crumb.fieldName === "string") {
      names.push(window.crumb.fieldName);
    }
    Array.prototype.slice.call(form.querySelectorAll("input[type=hidden]")).forEach(function (input) {
      if (names.indexOf(input.name) !== -1) {
        input.remove();
      }
    });
  },
  true,
);

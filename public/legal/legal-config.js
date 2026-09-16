/*
 * THE ONE PLACE TO FILL IN YOUR LEGAL DETAILS.
 *
 * Every page in this folder (terms.html, privacy.html, delete-account.html)
 * reads these values, both on the website and inside the app, which bundles
 * this folder as its assets. Edit here, then:
 *   - website:  firebase deploy --only hosting
 *   - app:      rebuild the APK (the folder is packaged at build time)
 *
 * Anything still in [BRACKETS] shows up highlighted in orange so it cannot be
 * missed. Replace every one before publishing on Google Play.
 */
window.LEGAL = {
  // Who runs LootLevel. An individual's full legal name, or a company's
  // registered name.
  operatorName: "[OPERATOR NAME]",

  // Country whose laws govern the Terms (usually where you live or the
  // company is registered).
  country: "[COUNTRY]",

  // A postal address. Google Play and GDPR expect one for the privacy contact.
  address: "[POSTAL ADDRESS]",

  // Support, privacy and account-deletion requests all go here.
  contactEmail: "[CONTACT EMAIL]",

  // The date these versions take effect, e.g. "1 October 2026". Update it
  // whenever you change the Terms or the Privacy Policy.
  effectiveDate: "[EFFECTIVE DATE]",

  // Where these pages are hosted.
  websiteUrl: "https://pixelpayout-check.web.app",
};

(function fillLegalPlaceholders() {
  function apply() {
    var legal = window.LEGAL || {};
    document.querySelectorAll("[data-legal]").forEach(function (el) {
      var key = el.getAttribute("data-legal");
      var value = legal[key];
      if (value === undefined) return;
      el.textContent = value;
      if (/^\[.*\]$/.test(value)) el.classList.add("todo");
      if (el.tagName === "A" && key === "contactEmail") {
        el.setAttribute("href", "mailto:" + value);
      }
      if (el.tagName === "A" && key === "websiteUrl") {
        el.setAttribute("href", value);
      }
    });
    // Inside the app the native screen already shows the title and a close
    // button, so the page drops its own header.
    if (/LootLevelApp/.test(navigator.userAgent)) {
      document.documentElement.classList.add("inapp");
    }
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", apply);
  } else {
    apply();
  }
})();

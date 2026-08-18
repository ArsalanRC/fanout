/**
 * Shared page chrome: language toggle, theme toggle, and the translator.
 *
 * Every repo page is bilingual and every repo page has a theme switch, so this
 * is the part that would otherwise be copied fifteen times and diverge fifteen
 * ways. The page supplies its own STRINGS and its own animations; this file
 * only handles the two controls in the header and the swapping of text.
 *
 * Usage, from the page's own app.js:
 *
 *     import { STRINGS } from "./i18n.js";
 *     import { initChrome, prefersReduced, sleep } from "./chrome.js";
 *
 *     const chrome = initChrome(STRINGS, { prefix: "recon" });
 *     chrome.onLangChange(() => redrawAnythingCanvasBased());
 *
 * Required keys in every STRINGS dictionary:
 *   meta.title, nav.lang, theme.toLight, theme.toDark
 */

const root = document.documentElement;

/** Read once. A page that respects this has to know before it animates. */
export const prefersReduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

/** Sleep that collapses to nothing when motion is reduced, so a scene with
 *  reduced motion completes instantly rather than being skipped entirely. */
export const sleep = (ms) => new Promise((r) => setTimeout(r, prefersReduced ? 0 : ms));

/**
 * Wire up the header controls and translate the page.
 *
 * @param {object} strings  { en: {...}, de: {...} }
 * @param {object} options
 * @param {string} options.prefix  localStorage namespace, so two of these
 *   pages open in the same browser do not fight over one another's theme.
 * @returns {{ lang: () => string, theme: () => string, onLangChange: (fn) => void }}
 */
export function initChrome(strings, { prefix = "site" } = {}) {
  const themeBtn = document.getElementById("theme-btn");
  const langBtn = document.getElementById("lang-btn");
  const listeners = [];

  const dictFor = (lang) => strings[lang] ?? strings.en;

  function labelTheme() {
    if (!themeBtn) return;
    const dict = dictFor(root.lang);
    const key = root.dataset.theme === "dark" ? "theme.toLight" : "theme.toDark";
    themeBtn.setAttribute("aria-label", dict[key] ?? "Switch theme");
  }

  function applyLang(lang) {
    const dict = dictFor(lang);
    root.lang = lang;

    for (const el of document.querySelectorAll("[data-i18n]")) {
      const value = dict[el.dataset.i18n];
      if (value !== undefined) el.textContent = value;
    }
    // A separate attribute for the few strings that legitimately carry markup,
    // so the common path never assigns innerHTML.
    for (const el of document.querySelectorAll("[data-i18n-html]")) {
      const value = dict[el.dataset.i18nHtml];
      if (value !== undefined) el.innerHTML = value;
    }

    if (dict["meta.title"]) document.title = dict["meta.title"];
    labelTheme();
    try { localStorage.setItem(`${prefix}-lang`, lang); } catch { /* private mode */ }

    // Canvas and SVG that were drawn with baked-in text will not re-translate
    // themselves, so pages that have any get told to redraw.
    for (const fn of listeners) fn(lang);
  }

  function applyTheme(theme) {
    root.dataset.theme = theme;
    labelTheme();
    try { localStorage.setItem(`${prefix}-theme`, theme); } catch { /* private mode */ }
  }

  themeBtn?.addEventListener("click", () => {
    applyTheme(root.dataset.theme === "dark" ? "light" : "dark");
  });

  langBtn?.addEventListener("click", () => {
    applyLang(root.lang === "de" ? "en" : "de");
  });

  // The inline script in <head> already picked the theme and language before
  // first paint, to avoid a flash. This pass fills in the actual text.
  applyLang(root.lang === "de" ? "de" : "en");

  return {
    lang: () => root.lang,
    theme: () => root.dataset.theme,
    onLangChange: (fn) => listeners.push(fn),
  };
}

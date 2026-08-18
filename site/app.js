/*
  fanout, the page.

  It replays searches that really ran. The files under data/ were written by
  PageData, which starts both services, goes over a socket and records what came
  back. PageDataTest re-runs them and fails when the committed answer stops
  matching what the code produces, so the page cannot drift into showing last
  month's behaviour.

  The lanes run at 1x. Every bar takes exactly as long as that supplier took,
  because scaling the clock to make the animation prettier would be the one
  thing this page is not allowed to do.

  Sorting is done here rather than in the engine on purpose. Ranking already
  priced results is presentation; deciding what a fare costs is not, and that
  stays in Java where it has tests.
*/

import { STRINGS } from "./i18n.js";
import { initChrome, prefersReduced } from "./chrome.js";

const MARKS = {
  FE: "fineair", BZ: "bizzair", AL: "altair",
  HY: "halcyon", NV: "nordvel", KE: "kestrel",
};

const PLACES = {
  CGN: "Cologne Bonn (CGN)", STN: "London Stansted (STN)",
  FRA: "Frankfurt (FRA)", LHR: "London Heathrow (LHR)",
  BER: "Berlin (BER)", LGW: "London Gatwick (LGW)",
  MUC: "Munich (MUC)", DUB: "Dublin (DUB)",
  BCN: "Barcelona (BCN)", VIE: "Vienna (VIE)",
  MAD: "Madrid (MAD)", AMS: "Amsterdam (AMS)", LIS: "Lisbon (LIS)",
};

/* Every market recorded. Seven routes, weekly for three months, and the page
   only offers what exists: a control with no file behind it is a button that
   shows the wrong answer. */
const ROUTES = ["CGN-STN", "FRA-LHR", "BER-LGW", "MUC-DUB", "BER-BCN", "VIE-MAD", "AMS-LIS"];
const DATES = Array.from({ length: 13 }, (_, week) => {
  const day = new Date(Date.UTC(2026, 8, 1) + week * 7 * 86400000);
  return day.toISOString().slice(0, 10);
});

const $ = (s) => document.querySelector(s);
const state = { route: "CGN-STN", date: DATES[0], basket: "cabin", budget: "3000", sort: "best", data: {}, timers: [] };

const chrome = initChrome(STRINGS, { prefix: "fanout" });
const say = (key) => STRINGS[chrome.lang()]?.[key] ?? STRINGS.en[key] ?? key;
const locale = () => (chrome.lang() === "de" ? "de-DE" : "en-GB");

const money = (m) =>
  (m.minor / 100).toLocaleString(locale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  + " " + m.currency;

const hhmm = (iso) =>
  new Date(iso).toLocaleTimeString(locale(), { hour: "2-digit", minute: "2-digit", timeZone: "UTC" });

const hm = (mins) => Math.floor(mins / 60) + "h " + String(mins % 60).padStart(2, "0") + "m";

// --------------------------------------------------------------------- data

const file = () =>
  `data/search-${state.route.toLowerCase()}-${state.date}-${state.basket}-${state.budget}.json`;

async function load() {
  const key = file();
  if (!state.data[key]) state.data[key] = await fetch(key).then((r) => r.json());
  return state.data[key];
}

// ------------------------------------------------------------------ ranking

/**
 * The four orderings a metasearch offers, and what each one means.
 *
 * "Best" is the only one that is a judgement rather than a fact, so it is
 * written down instead of tuned until the demo looks good: price normalised
 * against the cheapest, duration against the shortest, weighted seven to three.
 * A traveller will accept twenty minutes longer to save real money and will not
 * accept an hour longer to save two euros.
 */
function rank(rows, how) {
  const cheapest = Math.min(...rows.map((r) => r.best.total.minor));
  const quickest = Math.min(...rows.map((r) => r.duration_min));

  const score = (r) =>
    0.7 * (r.best.total.minor / cheapest) + 0.3 * (r.duration_min / quickest);

  const by = {
    best: (a, b) => score(a) - score(b),
    cheap: (a, b) => a.best.total.minor - b.best.total.minor,
    fast: (a, b) => a.duration_min - b.duration_min,
    early: (a, b) => Date.parse(a.legs[0].departure) - Date.parse(b.legs[0].departure),
  };
  return rows.slice().sort(by[how] ?? by.best);
}

// ------------------------------------------------------------------- replay

function stop() {
  state.timers.forEach((id) => { clearTimeout(id); clearInterval(id); });
  state.timers = [];
}

async function run() {
  stop();
  const search = await load();
  const budget = Number(search.budget_ms);
  const landed = new Set();

  $("#budget-mark").textContent = budget + " ms";
  $("#to").textContent = PLACES[state.route.split("-")[1]] ?? state.route.split("-")[1];

  const lanes = $("#lanes");
  lanes.innerHTML = search.suppliers.map((s) => `
    <div class="lane" data-for="${s.supplier}" data-width="${Math.min(100, (s.took_ms / budget) * 100).toFixed(2)}">
      <span class="who">${s.supplier}</span>
      <span class="track"><i></i></span>
      <span class="ms">0 ms</span>
      <span class="state">${say("state.waiting")}</span>
    </div>`).join("");

  render(search, landed);

  if (prefersReduced) {
    search.suppliers.forEach((s) => settle(s, search, landed, true));
    return;
  }

  search.suppliers.forEach((supplier) => {
    const lane = lanes.querySelector(`[data-for="${supplier.supplier}"]`);
    const took = Math.max(supplier.took_ms, 1);

    requestAnimationFrame(() => {
      const bar = lane.querySelector("i");
      bar.style.transition = `right ${took}ms linear`;
      bar.style.right = (100 - Number(lane.dataset.width)) + "%";
    });

    let shown = 0;
    const ticking = setInterval(() => {
      shown = Math.min(took, shown + 20);
      lane.querySelector(".ms").textContent = Math.round(shown) + " ms";
    }, 20);
    state.timers.push(ticking);
    state.timers.push(setTimeout(() => {
      clearInterval(ticking);
      settle(supplier, search, landed, false);
    }, took));
  });
}

function settle(supplier, search, landed, instant) {
  const lane = $(`#lanes [data-for="${supplier.supplier}"]`);
  if (!lane) return;

  lane.classList.add(supplier.status === "ANSWERED" ? "done"
    : supplier.status === "FAILED" ? "broke" : "late");
  lane.querySelector(".state").textContent = say("state." + supplier.status);
  lane.querySelector(".ms").textContent = supplier.took_ms + " ms";
  if (instant) lane.querySelector("i").style.right = (100 - Number(lane.dataset.width)) + "%";

  if (supplier.status === "ANSWERED") landed.add(supplier.supplier);
  render(search, landed);

  const settled = search.suppliers.filter(
    (s) => s.status !== "ANSWERED" || landed.has(s.supplier)).length;
  if (settled === search.suppliers.length) finish(search);
}

// ------------------------------------------------------------------ results

function render(search, landed) {
  const rows = search.itineraries
    .map((row) => {
      const offers = row.offers.filter((o) => landed.has(o.supplier));
      if (!offers.length) return null;
      const best = offers.reduce((a, b) => (a.total.minor <= b.total.minor ? a : b));
      return { ...row, offers, best };
    })
    .filter(Boolean);

  const board = $("#results");
  if (!rows.length) {
    board.innerHTML = `<p class="empty">${say("res.empty")}</p>`;
    return;
  }

  const ordered = rank(rows, state.sort);
  const cheapest = Math.min(...rows.map((r) => r.best.total.minor));
  const quickest = Math.min(...rows.map((r) => r.duration_min));

  // On a single route every flight takes the same time, so a "fastest" badge
  // would sit on every row and mean nothing. Only worth saying when some rows
  // are genuinely slower than others.
  const durationsDiffer = new Set(rows.map((r) => r.duration_min)).size > 1;

  board.innerHTML = ordered.map((row) => {
    const leg = row.legs[0];
    const code = leg.carrier;
    const tags = [];
    if (row.best.total.minor === cheapest) tags.push(`<span class="tag cheap">${say("res.cheapest")}</span>`);
    if (durationsDiffer && row.duration_min === quickest) {
      tags.push(`<span class="tag fast">${say("res.fastest")}</span>`);
    }

    const sellers = row.offers.length === 1
      ? `1 ${say("res.seller")}`
      : `${row.offers.length} ${say("res.sellers")}`;

    return `<article class="result">
      <div class="airline">
        <img src="logos/${MARKS[code]}-mark.svg" alt="" width="34" height="34" loading="lazy">
        <div><b>${row.carrier}</b><small>${leg.carrier}${leg.number}${leg.aircraft ? " · " + leg.aircraft : ""}</small></div>
      </div>
      <div class="times">
        <div class="clock"><b>${hhmm(leg.departure)}</b><span class="port">${leg.origin}</span></div>
        <div class="span"><span class="dur">${hm(row.duration_min)}</span><span class="line"></span><span class="stops">${say("res.direct")}</span></div>
        <div class="clock"><b>${hhmm(row.legs[row.legs.length - 1].arrival)}</b><span class="port">${row.legs[row.legs.length - 1].destination}</span></div>
      </div>
      <div class="buy">
        ${tags.length ? `<div class="tags">${tags.join("")}</div>` : ""}
        <div class="amount">${money(row.best.total)}</div>
        <div class="who-sells">${say("res.from")} ${row.best.supplier} · ${sellers}</div>
        <span class="btn btn-sm">${say("res.select")}</span>
      </div>
    </article>`;
  }).join("");
}

function finish(search) {
  const answered = search.suppliers.filter((s) => s.status === "ANSWERED");
  const first = answered.length ? Math.min(...answered.map((s) => s.took_ms)) : 0;

  $("#completeness").textContent = search.complete ? say("out.complete") : say("out.partial");
  $("#completeness").className = "note " + (search.complete ? "ok" : "warn");

  $("#readout").innerHTML = `
    <dl class="stat"><dt>${say("out.answered")}</dt>
      <dd class="${search.complete ? "good" : "warn"}">${answered.length}/${search.suppliers.length}</dd></dl>
    <dl class="stat"><dt>${say("out.cheapest")}</dt>
      <dd>${search.itineraries.length ? money(search.itineraries[0].best) : "—"}</dd></dl>
    <dl class="stat"><dt>${say("out.first")}</dt><dd>${first} ms</dd></dl>
    <dl class="stat"><dt>${say("out.dropped")}</dt>
      <dd class="${search.dropped.lapsed ? "warn" : ""}">${search.dropped.lapsed}</dd></dl>`;
}

// --------------------------------------------------------------------- hero

/** The same fan-out, smaller and on a loop, as the thing the hero shows. */
async function heroLoop() {
  const search = await fetch("data/search-cgn-stn-2026-09-01-cabin-300.json").then((r) => r.json());
  const lanes = $("#hero-lanes");
  const budget = Number(search.budget_ms);

  lanes.innerHTML = search.suppliers.map((s) => `
    <div class="hlane"><span>${s.supplier}</span><span class="htrack"><i></i></span></div>`).join("");

  if (prefersReduced) {
    lanes.querySelectorAll("i").forEach((bar, i) => {
      bar.style.right = (100 - (search.suppliers[i].took_ms / budget) * 100) + "%";
    });
    $("#hero-count").textContent = "4/4";
    $("#hero-clock").textContent = Math.max(...search.suppliers.map((s) => s.took_ms)) + " ms";
    return;
  }

  const bars = [...lanes.querySelectorAll("i")];
  const slowest = Math.max(...search.suppliers.map((s) => s.took_ms));

  const cycle = () => {
    let landed = 0;
    bars.forEach((bar, i) => {
      const took = search.suppliers[i].took_ms;
      bar.style.transition = "none";
      bar.style.right = "100%";
      bar.classList.remove("late");
      requestAnimationFrame(() => {
        bar.style.transition = `right ${took}ms linear`;
        bar.style.right = (100 - (took / budget) * 100) + "%";
      });
      setTimeout(() => {
        if (search.suppliers[i].status === "ANSWERED") landed++;
        else bar.classList.add("late");
        $("#hero-count").textContent = landed + "/" + search.suppliers.length;
        $("#hero-clock").textContent = took + " ms";
      }, took);
    });
    setTimeout(() => { $("#hero-clock").textContent = slowest + " ms"; }, slowest);
  };

  cycle();
  setInterval(cycle, slowest + 2200);
}

// --------------------------------------------------------------------- wire

function fillPickers() {
  const long = (d) => new Date(d + "T00:00:00Z").toLocaleDateString(locale(),
    { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

  $("#route").innerHTML = ROUTES
    .map((r) => `<option value="${r}"${r === state.route ? " selected" : ""}>${PLACES[r.split("-")[0]]}</option>`)
    .join("");
  $("#date").innerHTML = DATES
    .map((d) => `<option value="${d}"${d === state.date ? " selected" : ""}>${long(d)}</option>`)
    .join("");
  $("#to").textContent = PLACES[state.route.split("-")[1]];
}

$("#finder").addEventListener("submit", (e) => {
  e.preventDefault();
  state.route = $("#route").value;
  state.date = $("#date").value;
  state.basket = $("#basket").value;
  state.budget = $("#budget").value;
  run();
});

$("#route").addEventListener("change", () => {
  $("#to").textContent = PLACES[$("#route").value.split("-")[1]];
});

$("#sorts").addEventListener("click", (e) => {
  const button = e.target.closest("button");
  if (!button) return;
  $("#sorts").querySelectorAll("button").forEach((b) => b.classList.toggle("on", b === button));
  state.sort = button.dataset.sort;
  $("#sort-hint").textContent = say("sort.hint." + state.sort);
  load().then((search) => render(search, new Set(
    search.suppliers.filter((s) => s.status === "ANSWERED").map((s) => s.supplier))));
});

// Re-render on a language change: prices, times and labels are all formatted.
chrome.onLangChange(() => {
  $("#sort-hint").textContent = say("sort.hint." + state.sort);
  fillPickers();
  load().then((search) => {
    render(search, new Set(search.suppliers
      .filter((s) => s.status === "ANSWERED").map((s) => s.supplier)));
    finish(search);
  });
});

$("#sort-hint").textContent = say("sort.hint.best");
fillPickers();
heroLoop();

// Wait until the search is on screen before replaying it, so nobody arrives at
// a finished animation they never saw.
const watcher = new IntersectionObserver((entries) => {
  if (entries.some((e) => e.isIntersecting)) {
    watcher.disconnect();
    run();
  }
}, { threshold: 0.15 });
watcher.observe($("#search"));

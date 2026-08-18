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
  CGN: "Cologne CGN", STN: "London STN", FRA: "Frankfurt FRA", LHR: "London LHR",
  BER: "Berlin BER", LGW: "London LGW", MUC: "Munich MUC", DUB: "Dublin DUB",
  BCN: "Barcelona BCN", VIE: "Vienna VIE", MAD: "Madrid MAD",
  AMS: "Amsterdam AMS", LIS: "Lisbon LIS",
};

/* Every market recorded: seven city pairs, both directions, weekly for three
   months. The page only offers what exists, because a control with no file
   behind it is a button that shows the wrong answer. */
const PAIRS = ["CGN-STN", "FRA-LHR", "BER-LGW", "MUC-DUB", "BER-BCN", "VIE-MAD", "AMS-LIS"];
const ROUTES = PAIRS.flatMap((p) => [p, p.split("-").reverse().join("-")]);
const DATES = Array.from({ length: 13 }, (_, week) =>
  new Date(Date.UTC(2026, 8, 1) + week * 7 * 86400000).toISOString().slice(0, 10));

/** Where you can fly from here, so the destination list is never a dead end. */
const destinations = (from) =>
  ROUTES.filter((r) => r.startsWith(from + "-")).map((r) => r.split("-")[1]);

const origins = () => [...new Set(ROUTES.map((r) => r.split("-")[0]))];

const $ = (s) => document.querySelector(s);
const state = {
  trip: "one", from: "CGN", to: "STN", out: DATES[0], back: DATES[1],
  pax: 1, basket: "cabin", sort: "best", markets: {}, timers: [], picking: null,
};

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

const marketFile = (route, date) => `data/search-${route.toLowerCase()}-${date}.json`;

async function market(route, date) {
  const key = route + date;
  if (!state.markets[key]) {
    state.markets[key] = fetch(marketFile(route, date)).then((r) => r.json());
  }
  return state.markets[key];
}

/** One recorded search: a market, and the basket and party size inside it. */
async function search(route, date) {
  const file = await market(route, date);
  const want = `${state.basket}-${state.pax}`;
  return file.variants.find((v) => v.key === want).search;
}

/**
 * The outbound, and the way back when one was asked for.
 *
 * A return here is two one-way searches added together, which is what a
 * metasearch does when no through fare exists. It will never show a return
 * discount, and pretending otherwise would mean inventing a number.
 */
async function legsOfTrip() {
  const out = await search(state.from + "-" + state.to, state.out);
  if (state.trip === "one") return { out, back: null };
  return { out, back: await search(state.to + "-" + state.from, state.back) };
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
function rank(rows, how, totalOf) {
  const cheapest = Math.min(...rows.map(totalOf));
  const quickest = Math.min(...rows.map((r) => r.duration_min));

  const score = (r) => 0.7 * (totalOf(r) / cheapest) + 0.3 * (r.duration_min / quickest);

  const by = {
    best: (a, b) => score(a) - score(b),
    cheap: (a, b) => totalOf(a) - totalOf(b),
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
  const trip = await legsOfTrip();
  const search = trip.out;
  const budget = Number(search.budget_ms);
  const landed = new Set();

  $("#budget-mark").textContent = budget + " ms";

  const lanes = $("#lanes");
  lanes.innerHTML = search.suppliers.map((s) => `
    <div class="lane" data-for="${s.supplier}" data-width="${Math.min(100, (s.took_ms / budget) * 100).toFixed(2)}">
      <span class="who">${s.supplier}</span>
      <span class="track"><i></i></span>
      <span class="ms">0 ms</span>
      <span class="state">${say("state.waiting")}</span>
    </div>`).join("");

  render(trip, landed);

  if (prefersReduced) {
    search.suppliers.forEach((s) => settle(s, trip, landed, true));
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
      settle(supplier, trip, landed, false);
    }, took));
  });
}

function settle(supplier, trip, landed, instant) {
  const lane = $(`#lanes [data-for="${supplier.supplier}"]`);
  if (!lane) return;

  lane.classList.add(supplier.status === "ANSWERED" ? "done"
    : supplier.status === "FAILED" ? "broke" : "late");
  lane.querySelector(".state").textContent = say("state." + supplier.status);
  lane.querySelector(".ms").textContent = supplier.took_ms + " ms";
  if (instant) lane.querySelector("i").style.right = (100 - Number(lane.dataset.width)) + "%";

  if (supplier.status === "ANSWERED") landed.add(supplier.supplier);
  render(trip, landed);

  const settled = trip.out.suppliers.filter(
    (s) => s.status !== "ANSWERED" || landed.has(s.supplier)).length;
  if (settled === trip.out.suppliers.length) finish(trip.out);
}

// ------------------------------------------------------------------ results

/** Rows that have landed, with their cheapest offer among the suppliers in. */
function landedRows(search, landed) {
  return search.itineraries
    .map((row) => {
      const offers = row.offers.filter((o) => landed.has(o.supplier));
      if (!offers.length) return null;
      const best = offers.reduce((a, b) => (a.total.minor <= b.total.minor ? a : b));
      return { ...row, offers, best };
    })
    .filter(Boolean);
}

function render(trip, landed) {
  const rows = landedRows(trip.out, landed);
  const board = $("#results");

  if (!rows.length) {
    board.innerHTML = `<p class="empty">${say("res.empty")}</p>`;
    return;
  }

  // The way back is already complete: it was recorded, not raced. Its cheapest
  // is added to every outbound so the price on the row is what the trip costs.
  const inbound = trip.back
    ? trip.back.itineraries.reduce((a, b) => (a.best.minor <= b.best.minor ? a : b), trip.back.itineraries[0])
    : null;

  const totalOf = (row) => row.best.total.minor + (inbound ? inbound.best.minor : 0);

  const ordered = rank(rows, state.sort, totalOf);
  const cheapest = Math.min(...rows.map(totalOf));
  const quickest = Math.min(...rows.map((r) => r.duration_min));
  // Only worth a badge when exactly one row holds it. Two flights tied for
  // fastest and both wearing the label says nothing to choose between them.
  const fastestCount = rows.filter((r) => r.duration_min === quickest).length;
  const durationsDiffer = new Set(rows.map((r) => r.duration_min)).size > 1 && fastestCount === 1;

  board.innerHTML = ordered.map((row) => {
    const first = row.legs[0];
    const last = row.legs[row.legs.length - 1];
    const total = totalOf(row);

    const tags = [];
    if (total === cheapest) tags.push(`<span class="tag cheap">${say("res.cheapest")}</span>`);
    if (durationsDiffer && row.duration_min === quickest) {
      tags.push(`<span class="tag fast">${say("res.fastest")}</span>`);
    }

    const stops = row.stops === 0
      ? `<span class="stops">${say("res.direct")}</span>`
      : `<span class="stops via">${say("res.via")} ${row.legs.slice(0, -1).map((l) => l.destination).join(", ")}</span>`;

    const each = state.pax > 1
      ? `<div class="each">${money({ minor: Math.round(total / state.pax), currency: row.best.total.currency })} ${say("res.each")}</div>`
      : "";

    const returnNote = inbound
      ? `<div class="who-sells">${say("res.bothways")}</div>`
      : `<div class="who-sells">${say("res.from")} ${row.best.supplier}</div>`;

    return `<article class="result">
      <div class="airline">
        <img src="logos/${MARKS[first.carrier]}-mark.svg" alt="" width="34" height="34" loading="lazy">
        <div><b>${row.carrier}</b><small>${row.legs.map((l) => l.carrier + l.number).join(" · ")}${first.aircraft ? " · " + first.aircraft : ""}</small></div>
      </div>
      <div class="times">
        <div class="clock"><b>${hhmm(first.departure)}</b><span class="port">${first.origin}</span></div>
        <div class="span"><span class="dur">${hm(row.duration_min)}</span><span class="line"></span>${stops}</div>
        <div class="clock"><b>${hhmm(last.arrival)}</b><span class="port">${last.destination}</span></div>
      </div>
      <div class="buy">
        ${tags.length ? `<div class="tags">${tags.join("")}</div>` : ""}
        <div class="amount">${money({ minor: total, currency: row.best.total.currency })}</div>
        ${each}
        ${returnNote}
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
      <dd>${search.itineraries.length ? money(search.itineraries[0].best) : "\u2014"}</dd></dl>
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

// ----------------------------------------------------------------- calendar

/**
 * A month grid, with only the recorded dates selectable.
 *
 * Every other day is rendered and disabled rather than hidden, because a
 * calendar with holes in it is confusing and a calendar that only shows
 * thirteen days is not a calendar. What it cannot do, it says it cannot do.
 */
function drawCalendar(monthOf) {
  const grid = $("#cal-grid");
  const first = new Date(Date.UTC(monthOf.getUTCFullYear(), monthOf.getUTCMonth(), 1));
  const lead = (first.getUTCDay() + 6) % 7;            // weeks start on Monday
  const days = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0)).getUTCDate();

  $("#cal-month").textContent = first.toLocaleDateString(locale(),
    { month: "long", year: "numeric", timeZone: "UTC" });

  $("#cal-days").innerHTML = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
    .map((d, i) => `<span>${new Date(Date.UTC(2026, 5, 1 + i))
      .toLocaleDateString(locale(), { weekday: "short", timeZone: "UTC" }).slice(0, 2)}</span>`)
    .join("");

  const chosen = state.picking === "back" ? state.back : state.out;
  const cells = [];
  for (let i = 0; i < lead; i++) cells.push('<span class="cal-cell empty"></span>');

  for (let day = 1; day <= days; day++) {
    const iso = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth(), day))
      .toISOString().slice(0, 10);
    const open = DATES.includes(iso)
      && (state.picking !== "back" || iso >= state.out);
    cells.push(`<button type="button" class="cal-cell${open ? "" : " shut"}${iso === chosen ? " on" : ""}"
      ${open ? "" : "disabled"} data-date="${iso}">${day}</button>`);
  }
  grid.innerHTML = cells.join("");
}

function openCalendar(which) {
  state.picking = which;
  state.calMonth = new Date((which === "back" ? state.back : state.out) + "T00:00:00Z");
  $("#calendar").hidden = false;
  drawCalendar(state.calMonth);
}

$("#calendar").addEventListener("click", (e) => {
  const cell = e.target.closest(".cal-cell");
  if (!cell || cell.disabled) return;

  if (state.picking === "back") state.back = cell.dataset.date;
  else {
    state.out = cell.dataset.date;
    // A return before the outbound is not a trip. Push it along rather than
    // refusing, which is what every booking site does.
    if (state.back < state.out) {
      state.back = DATES.find((d) => d > state.out) ?? state.out;
    }
  }
  $("#calendar").hidden = true;
  paintForm();
});

$("#cal-prev").addEventListener("click", () => {
  state.calMonth.setUTCMonth(state.calMonth.getUTCMonth() - 1);
  drawCalendar(state.calMonth);
});
$("#cal-next").addEventListener("click", () => {
  state.calMonth.setUTCMonth(state.calMonth.getUTCMonth() + 1);
  drawCalendar(state.calMonth);
});

// --------------------------------------------------------------------- form

const longDate = (iso) => new Date(iso + "T00:00:00Z")
  .toLocaleDateString(locale(), { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });

function paintForm() {
  $("#from").innerHTML = origins()
    .map((a) => `<option value="${a}"${a === state.from ? " selected" : ""}>${PLACES[a]}</option>`)
    .join("");

  const open = destinations(state.from);
  if (!open.includes(state.to)) state.to = open[0];
  $("#to").innerHTML = open
    .map((a) => `<option value="${a}"${a === state.to ? " selected" : ""}>${PLACES[a]}</option>`)
    .join("");

  $("#out-btn").textContent = longDate(state.out);
  $("#back-btn").textContent = longDate(state.back);
  $("#back-field").hidden = state.trip === "one";
  $("#pax").textContent = state.pax;
  $("#basket").value = state.basket;
  $("#sort-hint").textContent = say("sort.hint." + state.sort);
}

$("#from").addEventListener("change", () => { state.from = $("#from").value; paintForm(); });
$("#to").addEventListener("change", () => { state.to = $("#to").value; });

$("#swap").addEventListener("click", () => {
  const [from, to] = [state.to, state.from];
  if (!ROUTES.includes(from + "-" + to)) return;
  state.from = from;
  state.to = to;
  paintForm();
});

document.querySelector(".trip").addEventListener("click", (e) => {
  const button = e.target.closest("button");
  if (!button) return;
  document.querySelectorAll(".trip button").forEach((b) => b.classList.toggle("on", b === button));
  state.trip = button.dataset.trip;
  paintForm();
});

$("#out-btn").addEventListener("click", () => openCalendar("out"));
$("#back-btn").addEventListener("click", () => openCalendar("back"));

$("#fewer").addEventListener("click", () => { state.pax = Math.max(1, state.pax - 1); paintForm(); });
$("#more").addEventListener("click", () => { state.pax = Math.min(4, state.pax + 1); paintForm(); });
$("#basket").addEventListener("change", () => { state.basket = $("#basket").value; });

$("#finder").addEventListener("submit", (e) => {
  e.preventDefault();
  $("#calendar").hidden = true;
  run();
});

$("#sorts").addEventListener("click", (e) => {
  const button = e.target.closest("button");
  if (!button) return;
  $("#sorts").querySelectorAll("button").forEach((b) => b.classList.toggle("on", b === button));
  state.sort = button.dataset.sort;
  $("#sort-hint").textContent = say("sort.hint." + state.sort);
  redraw();
});

/** Redraw the finished result set without replaying the race. */
async function redraw() {
  const trip = await legsOfTrip();
  render(trip, new Set(trip.out.suppliers
    .filter((s) => s.status === "ANSWERED").map((s) => s.supplier)));
  finish(trip.out);
}

chrome.onLangChange(() => { paintForm(); redraw(); });

/*
  The search in the query string, so a link points at one particular one.

  It also makes the page testable from a screenshot, which is the only way the
  calendar, the return leg and the party size get checked without a human
  clicking through four controls.
*/
const asked = new URLSearchParams(location.search);
const pick = (key, allowed, fallback) =>
  allowed.includes(asked.get(key)) ? asked.get(key) : fallback;

state.trip = pick("trip", ["one", "return"], "one");
state.from = pick("from", origins(), "CGN");
state.to = pick("to", destinations(state.from), destinations(state.from)[0]);
state.out = pick("out", DATES, DATES[0]);
state.back = pick("back", DATES.filter((d) => d >= state.out), DATES[1]);
state.pax = Number(pick("pax", ["1", "2", "3", "4"], "1"));
state.basket = pick("basket", ["cabin", "checked"], "cabin");

document.querySelectorAll(".trip button").forEach((b) =>
  b.classList.toggle("on", b.dataset.trip === state.trip));

paintForm();
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

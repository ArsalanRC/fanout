/*
  fanout, the page.

  It replays a search that really ran. The four files under data/ were written
  by PageData, which starts both services, goes over a socket and records what
  came back. A test re-runs them and fails when the committed answer stops
  matching what the code produces, so the page cannot quietly drift into
  showing last month's behaviour.

  The replay runs at 1x. Every bar takes exactly as long as that supplier took,
  because scaling the clock to make the animation prettier would be the one
  thing this page is not allowed to do.
*/

const COPY = {
  en: {
    'skip': 'Skip to the fan-out',
    'hero.eyebrow': 'Metasearch architecture · Java 21 · Zero runtime dependencies',
    'hero.line1': 'Eight suppliers.',
    'hero.line2': 'One deadline.',
    'hero.lede': 'Give every supplier its own timeout and the search costs the sum of them. One shared budget answers on time, and names whoever did not reply.',

    'stage.label': 'The fan-out',
    'axis.zero': 'Asked',
    'stage.note': 'Düsseldorf to London Stansted, one passenger. Every bar below runs for exactly as long as that supplier really took.',
    'ctl.cabin': 'Hand luggage',
    'ctl.checked': 'With a suitcase',
    'ctl.replay': 'Replay',
    'board.flight': 'Flight',
    'board.route': 'Route',
    'board.sellers': 'Sellers',
    'board.price': 'Best price',
    'board.waiting': 'Nobody has answered yet.',

    'state.ANSWERED': 'Answered',
    'state.TIMED_OUT': 'Too late',
    'state.FAILED': 'Failed',
    'state.SKIPPED': 'Skipped',
    'state.waiting': 'Asking',

    'stat.answered': 'Suppliers in',
    'stat.cheapest': 'Cheapest fare',
    'stat.first': 'First result',
    'stat.dropped': 'Dropped, lapsed',
    'stat.complete': 'Complete',
    'stat.partial': 'Partial',

    'answers.label': 'One question, three answers',
    'answers.title': 'There is no such thing as the price of a flight.',
    'answers.body': 'There is the price for somebody. A budget carrier sells the seat and charges for the rest. A full service carrier sells the bag inside the fare. Sorting on the headline answers a question nobody asked.',
    'answers.foot': 'Every figure here is read out of the recorded search, not typed in.',
    'q.headline': 'On the headline',
    'q.cabin': 'With hand luggage',
    'q.checked': 'With a suitcase',
    'versus': 'ahead of',
    'by': 'by',

    'args.label': 'Six ways this goes wrong',
    'arg1.h': 'One deadline, not eight timeouts',
    'arg1.p': 'The budget is set once, at the edge. Every call below gets what is left of it, never a fresh allowance.',
    'arg2.h': 'A partial answer beats none',
    'arg2.p': 'A supplier that misses the deadline is reported beside the results. Failing the search because one of eight was slow is the expensive mistake.',
    'arg3.h': 'Late is not broken',
    'arg3.p': 'A dead supplier stops being called. A slow one keeps being called, because counting slowness as failure drops suppliers that work.',
    'arg4.h': 'Normalisation hides the bugs',
    'arg4.p': 'Per passenger or per booking, different currencies, local time with no offset. Comparing the numbers as they arrive compares different things.',
    'arg5.h': 'One flight, one row',
    'arg5.p': 'The same seats sold by three suppliers is one row with three prices. Three rows look full and hide the spread.',
    'arg6.h': 'A lapsed fare is worse than none',
    'arg6.p': 'It sorts to the top, gets clicked, and fails where somebody was about to pay. So it is dropped, and counted.',

    'wire.label': 'The boundary',
    'wire.title': 'The budget survives the hop, or it is not a budget.',
    'wire.body': 'Two services, and the deadline crosses between them in a header. A budget that stops at a process boundary is a timeout with extra steps, and the service below quietly starts a fresh one.',
    'wire.r1h': 'Shorten it, never extend it.',
    'wire.r1p': 'The service starts at its own ceiling and takes whichever is sooner. Ask for ten minutes, get five seconds.',
    'wire.r2h': '504 and 502 are different answers.',
    'wire.r2p': 'Out of budget against actually broken. The breaker counts one and ignores the other.',
    'wire.r3h': 'Money crosses as minor units.',
    'wire.r3p': 'Never a decimal. Read back as a double, 21.49 times 100 is 2148.9999.',

    'honest.label': 'What is real here',
    'honest.t1': 'The airlines are invented',
    'honest.p1': 'Fineair, Bizzair, Altair and Halcyon do not exist. There is no free flight-price API left to record from, so putting invented prices beside a real brand was not worth doing.',
    'honest.t2': 'The timings are measured',
    'honest.p2': 'Every millisecond on this page came from a real search across two processes. Nothing here is rounded to look tidy, and no number was typed in by hand.',

    'footer.built': 'Built by',
    'footer.note': 'Fares and airlines are modelled. The code, the timings and the failures are not.'
  },

  de: {
    'skip': 'Zum Fan-out springen',
    'hero.eyebrow': 'Metasuch-Architektur · Java 21 · Keine Laufzeit-Abhängigkeiten',
    'hero.line1': 'Acht Anbieter.',
    'hero.line2': 'Ein Zeitbudget.',
    'hero.lede': 'Gibt man jedem Anbieter sein eigenes Timeout, kostet die Suche die Summe daraus. Ein gemeinsames Budget antwortet pünktlich und nennt jeden, von dem nichts kam.',

    'stage.label': 'Der Fan-out',
    'axis.zero': 'Gefragt',
    'stage.note': 'Düsseldorf nach London Stansted, eine Person. Jeder Balken läuft genau so lange, wie dieser Anbieter wirklich gebraucht hat.',
    'ctl.cabin': 'Nur Handgepäck',
    'ctl.checked': 'Mit Koffer',
    'ctl.replay': 'Nochmal',
    'board.flight': 'Flug',
    'board.route': 'Strecke',
    'board.sellers': 'Verkäufer',
    'board.price': 'Bester Preis',
    'board.waiting': 'Noch hat niemand geantwortet.',

    'state.ANSWERED': 'Geantwortet',
    'state.TIMED_OUT': 'Zu spät',
    'state.FAILED': 'Fehler',
    'state.SKIPPED': 'Übersprungen',
    'state.waiting': 'Wird gefragt',

    'stat.answered': 'Anbieter dabei',
    'stat.cheapest': 'Günstigster Tarif',
    'stat.first': 'Erstes Ergebnis',
    'stat.dropped': 'Verfallen, verworfen',
    'stat.complete': 'Vollständig',
    'stat.partial': 'Unvollständig',

    'answers.label': 'Eine Frage, drei Antworten',
    'answers.title': 'Den Preis eines Fluges gibt es nicht.',
    'answers.body': 'Es gibt den Preis für jemanden. Ein Billigflieger verkauft den Sitzplatz und rechnet den Rest ab. Eine Linienairline hat den Koffer schon im Tarif. Wer nach dem Schaufensterpreis sortiert, beantwortet eine Frage, die niemand gestellt hat.',
    'answers.foot': 'Jede Zahl hier stammt aus der aufgezeichneten Suche und ist nicht eingetippt.',
    'q.headline': 'Auf dem Preisschild',
    'q.cabin': 'Mit Handgepäck',
    'q.checked': 'Mit Koffer',
    'versus': 'vor',
    'by': 'um',

    'args.label': 'Sechs Arten, das falsch zu bauen',
    'arg1.h': 'Ein Zeitbudget statt acht Timeouts',
    'arg1.p': 'Das Budget wird einmal gesetzt, ganz außen. Jeder Aufruf darunter bekommt nur den Rest davon, nie eine frische Zuteilung.',
    'arg2.h': 'Eine Teilantwort schlägt keine',
    'arg2.p': 'Ein Anbieter, der das Budget reißt, steht neben den Ergebnissen. Die Suche scheitern zu lassen, weil einer von acht langsam war, ist der teure Fehler.',
    'arg3.h': 'Langsam ist nicht kaputt',
    'arg3.p': 'Ein toter Anbieter wird nicht mehr gefragt. Ein langsamer schon, denn wer Langsamkeit als Fehler zählt, wirft funktionierende Anbieter raus.',
    'arg4.h': 'In der Normalisierung stecken die Bugs',
    'arg4.p': 'Pro Person oder pro Buchung, andere Währungen, Ortszeit ohne Offset. Wer die Zahlen so vergleicht wie sie ankommen, vergleicht Verschiedenes.',
    'arg5.h': 'Ein Flug, eine Zeile',
    'arg5.p': 'Dieselben Plätze bei drei Anbietern sind eine Zeile mit drei Preisen. Drei Zeilen sehen voll aus und verstecken die Spanne.',
    'arg6.h': 'Ein verfallener Tarif ist schlimmer als keiner',
    'arg6.p': 'Er sortiert sich nach oben, wird angeklickt, und scheitert dort wo jemand zahlen wollte. Also fliegt er raus und wird gezählt.',

    'wire.label': 'Die Grenze',
    'wire.title': 'Das Budget übersteht den Sprung, sonst ist es keines.',
    'wire.body': 'Zwei Services, und die Frist geht als Header dazwischen über. Ein Budget, das an der Prozessgrenze endet, ist ein Timeout mit Umweg, und der Service darunter startet still ein neues.',
    'wire.r1h': 'Kürzen ja, verlängern nie.',
    'wire.r1p': 'Der Service startet bei seiner eigenen Obergrenze und nimmt den früheren Zeitpunkt. Wer zehn Minuten will, bekommt fünf Sekunden.',
    'wire.r2h': '504 und 502 sind zwei Antworten.',
    'wire.r2p': 'Budget alle gegen wirklich kaputt. Der Breaker zählt das eine und ignoriert das andere.',
    'wire.r3h': 'Geld reist in Minor Units.',
    'wire.r3p': 'Nie als Dezimalzahl. Als Double zurückgelesen ergibt 21,49 mal 100 genau 2148,9999.',

    'honest.label': 'Was hier echt ist',
    'honest.t1': 'Die Airlines sind erfunden',
    'honest.p1': 'Fineair, Bizzair, Altair und Halcyon gibt es nicht. Es gibt keine kostenlose Flugpreis-API mehr zum Aufzeichnen, und erfundene Preise neben einer echten Marke wären es nicht wert gewesen.',
    'honest.t2': 'Die Zeiten sind gemessen',
    'honest.p2': 'Jede Millisekunde hier stammt aus einer echten Suche über zwei Prozesse. Nichts ist glattgerundet, und keine Zahl wurde von Hand eingetragen.',

    'footer.built': 'Gebaut von',
    'footer.note': 'Tarife und Airlines sind modelliert. Der Code, die Zeiten und die Fehler nicht.'
  }
};

const MARKS = { FE: 'fineair', BZ: 'bizzair', AL: 'altair', HY: 'halcyon' };

const state = { lang: 'en', basket: 'cabin', budget: '3000', data: {}, timers: [] };

const $ = (sel) => document.querySelector(sel);
const say = (key) => COPY[state.lang][key] ?? key;

/* ------------------------------------------------------------------ i18n */

function paint() {
  document.documentElement.lang = state.lang;
  document.querySelectorAll('[data-i18n]').forEach((node) => {
    node.textContent = say(node.dataset.i18n);
  });
  document.querySelectorAll('.lang button').forEach((b) => {
    b.classList.toggle('on', b.dataset.lang === state.lang);
  });
}

/* ------------------------------------------------------------------ money */

const money = (m) =>
  (m.minor / 100).toLocaleString(state.lang === 'de' ? 'de-DE' : 'en-GB',
    { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + m.currency;

/* ------------------------------------------------------------------- data */

const file = () =>
  'data/search-' + (state.basket === 'checked' ? 'checked' : 'cabin')
  + (state.budget === '300' ? (state.basket === 'checked' ? '-tight' : '') : '') + '.json';

function name() {
  if (state.basket === 'checked') return state.budget === '300' ? 'search-checked-tight' : 'search-checked';
  return state.budget === '300' ? 'search-tight' : 'search-cabin';
}

async function load() {
  const key = name();
  if (!state.data[key]) {
    const response = await fetch('data/' + key + '.json');
    state.data[key] = await response.json();
  }
  return state.data[key];
}

/* ----------------------------------------------------------------- replay */

function stop() {
  // Both, because these are a mix of timeouts and intervals. The two share an
  // id space, so one call happens to clear the other, and relying on that is
  // the kind of thing that stops being true.
  state.timers.forEach((id) => { clearTimeout(id); clearInterval(id); });
  state.timers = [];
}

const still = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

async function run() {
  stop();
  const search = await load();

  const lanes = $('#lanes');
  lanes.innerHTML = '';

  const landed = new Set();

  /*
    The track is the budget, end to end. So a bar is how much of the budget
    that supplier spent, and the empty space to its right is what was left.

    Scaling each bar to the slowest supplier instead would fill the row and
    look better, and it would throw away the only thing worth seeing: at 3000ms
    nobody comes close, at 300ms one of them hits the wall.
  */
  const budget = Number(search.budget_ms);

  search.suppliers.forEach((supplier) => {
    const lane = document.createElement('div');
    lane.className = 'lane';
    lane.dataset.for = supplier.supplier;
    lane.dataset.width = Math.min(100, (supplier.took_ms / budget) * 100).toFixed(2);
    lane.innerHTML = `<span class="who">${supplier.supplier}</span>
      <span class="track"><i></i></span>
      <span class="ms">0 ms</span>
      <span class="state">${say('state.waiting')}</span>`;
    lanes.appendChild(lane);
  });

  $('#budget-mark').textContent = budget + ' ms';

  render(search, landed);

  if (still()) {
    // Straight to the finished state. An animation somebody asked not to see
    // is not worth the information it carries.
    search.suppliers.forEach((s) => settle(s, search, landed, true));
    return;
  }

  search.suppliers.forEach((supplier) => {
    const lane = lanes.querySelector(`[data-for="${supplier.supplier}"]`);
    const bar = lane.querySelector('i');
    const took = Math.max(supplier.took_ms, 1);

    // 1x. The bar is the measurement, not a decoration of it.
    requestAnimationFrame(() => {
      bar.style.transition = `right ${took}ms linear`;
      bar.style.right = (100 - Number(lane.dataset.width)) + '%';
    });

    const ticking = setInterval(() => {
      const shown = Math.min(took, Number(lane.querySelector('.ms').dataset.at || 0) + 20);
      lane.querySelector('.ms').dataset.at = shown;
      lane.querySelector('.ms').textContent = Math.round(shown) + ' ms';
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

  const status = supplier.status;
  lane.classList.add(status === 'ANSWERED' ? 'done' : status === 'FAILED' ? 'broke' : 'late');
  lane.querySelector('.state').textContent = say('state.' + status);
  lane.querySelector('.ms').textContent = supplier.took_ms + ' ms';
  if (instant) lane.querySelector('i').style.right = (100 - Number(lane.dataset.width)) + '%';

  if (status === 'ANSWERED') landed.add(supplier.supplier);
  render(search, landed);

  if (landed.size + search.suppliers.filter((s) => s.status !== 'ANSWERED').length
      === search.suppliers.length) {
    readout(search);
  }
}

/* ------------------------------------------------------------------ board */

function render(search, landed) {
  const board = $('#rows');

  const rows = search.itineraries
    .map((row) => {
      const offers = row.offers.filter((o) => landed.has(o.supplier));
      if (!offers.length) return null;
      const best = offers.reduce((a, b) => (a.total.minor <= b.total.minor ? a : b));
      return { row, offers, best };
    })
    .filter(Boolean)
    .sort((a, b) => a.best.total.minor - b.best.total.minor);

  if (!rows.length) {
    board.innerHTML = `<p class="empty">${say('board.waiting')}</p>`;
    return;
  }

  const seen = new Set([...board.querySelectorAll('.row')].map((r) => r.dataset.key));

  board.innerHTML = rows.map(({ row, offers, best }) => {
    const code = row.legs[0].carrier;
    const flight = code + row.legs[0].number;
    const depart = new Date(row.legs[0].departure)
      .toLocaleTimeString(state.lang === 'de' ? 'de-DE' : 'en-GB',
        { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' });

    const chips = offers
      .slice()
      .sort((a, b) => a.total.minor - b.total.minor)
      .map((o) => `<span class="chip${o === best ? ' best' : ''}">${o.supplier}</span>`)
      .join('');

    const spread = offers.length > 1
      ? `<small>+${money({
          minor: Math.max(...offers.map((o) => o.total.minor)) - best.total.minor,
          currency: best.total.currency
        })}</small>`
      : '';

    return `<div class="row${seen.has(row.key) ? '' : ' fresh'}" data-key="${row.key}">
      <span class="flight">
        <img src="logos/${MARKS[code]}-mark.svg" alt="" width="22" height="22">
        <span><b>${flight}</b><small>${row.carrier}</small></span>
      </span>
      <span class="route">${row.origin} ${depart} ${row.destination}</span>
      <span class="chips">${chips}</span>
      <span class="price">${money(best.total)}${spread}</span>
    </div>`;
  }).join('');
}

function readout(search) {
  const answered = search.suppliers.filter((s) => s.status === 'ANSWERED');
  const first = answered.length ? Math.min(...answered.map((s) => s.took_ms)) : 0;
  const cheapest = search.itineraries.length ? money(search.itineraries[0].best) : '—';

  $('#readout').innerHTML = `
    <dl class="stat"><dt>${say('stat.answered')}</dt>
      <dd class="${search.complete ? 'good' : 'warn'}">${answered.length}/${search.suppliers.length}</dd></dl>
    <dl class="stat"><dt>${say('stat.cheapest')}</dt><dd>${cheapest}</dd></dl>
    <dl class="stat"><dt>${say('stat.first')}</dt><dd>${first} ms</dd></dl>
    <dl class="stat"><dt>${say('stat.dropped')}</dt>
      <dd class="${search.dropped.lapsed ? 'warn' : ''}">${search.dropped.lapsed}</dd></dl>`;
}

/* ---------------------------------------------------------- three answers */

async function answers() {
  const [cabin, checked] = await Promise.all([
    fetch('data/search-cabin.json').then((r) => r.json()),
    fetch('data/search-checked.json').then((r) => r.json())
  ]);

  /*
    Whoever looks cheapest before anything is counted, and whoever looks second
    cheapest, which has to be a different airline or the card compares one
    carrier with itself. Two Fineair flights are two rows and one answer.
  */
  const byBase = cabin.itineraries
    .flatMap((row) => row.offers.map((o) => ({ o, row })))
    .sort((a, b) => a.o.base.minor - b.o.base.minor);

  const headline = byBase[0];
  const behind = byBase.find((x) => x.row.carrier !== headline.row.carrier);

  // Same rule for the runner-up here: the next row belonging to a different
  // airline, not simply the next row.
  const cheapest = (search) => ({
    carrier: search.itineraries[0].carrier,
    amount: search.itineraries[0].best,
    runner: search.itineraries.find((row) => row.carrier !== search.itineraries[0].carrier)
            ?? search.itineraries[1]
  });

  const byCabin = cheapest(cabin);
  const byChecked = cheapest(checked);

  const card = (question, carrier, amount, runner, flipped) => `
    <div class="card${flipped ? ' flip' : ''}">
      <p class="q">${question}</p>
      <p class="winner">${carrier}</p>
      <p class="amount">${money(amount)}</p>
      <p class="versus">${say('versus')} ${runner.carrier}, ${say('by')} ${money({
        minor: runner.best.minor - amount.minor, currency: amount.currency })}</p>
    </div>`;

  $('#three').innerHTML =
    card(say('q.headline'), headline.row.carrier, headline.o.base,
      { carrier: behind.row.carrier, best: behind.o.base }, false)
    + card(say('q.cabin'), byCabin.carrier, byCabin.amount, byCabin.runner, false)
    + card(say('q.checked'), byChecked.carrier, byChecked.amount, byChecked.runner,
      byChecked.carrier !== byCabin.carrier);
}

/* ------------------------------------------------------------------- wire */

document.querySelectorAll('.lang button').forEach((button) => {
  button.addEventListener('click', () => {
    state.lang = button.dataset.lang;
    localStorage.setItem('fanout.lang', state.lang);
    paint();
    run();
    answers();
  });
});

document.querySelectorAll('.seg').forEach((group) => {
  group.addEventListener('click', (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    group.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === button));
    state[group.dataset.control] = button.dataset.value;
    run();
  });
});

$('#replay').addEventListener('click', run);

/*
  The query string wins over the stored preference, so a link can point at one
  particular search. `?basket=checked&budget=300&lang=de` is the partial one in
  German, which is the state worth sending somebody.
*/
const asked = new URLSearchParams(location.search);
const pick = (key, allowed, fallback) =>
  allowed.includes(asked.get(key)) ? asked.get(key) : fallback;

state.lang = pick('lang', ['en', 'de'],
  localStorage.getItem('fanout.lang') === 'de' ? 'de' : 'en');
state.basket = pick('basket', ['cabin', 'checked'], 'cabin');
state.budget = pick('budget', ['300', '3000'], '3000');

document.querySelectorAll('.seg').forEach((group) => {
  group.querySelectorAll('button').forEach((b) => {
    b.classList.toggle('on', b.dataset.value === state[group.dataset.control]);
  });
});

paint();
answers();

// Wait until the fan-out is on screen before replaying it, so nobody arrives
// at a finished animation they never saw.
const watcher = new IntersectionObserver((entries) => {
  if (entries.some((e) => e.isIntersecting)) {
    watcher.disconnect();
    run();
  }
}, { threshold: 0.2 });
watcher.observe($('#fanout'));

#!/usr/bin/env python3
"""Writes the modelled supplier payloads in this directory.

Run from the repository root:

    python3 integration/src/main/resources/fixtures/generate.py

Why a generator rather than twelve hand-written files. Each supplier speaks a
different shape, and the same flight has to appear in several of them with the
same times and a different price. Kept by hand, those drift: a departure gets
edited in one file and not the other, deduplication silently stops working, and
the demo shows two rows where it used to show one. One table, three writers.

WHAT IS REAL HERE
-----------------
The routes, the block times and the aircraft. Checked on 18 August 2026:

  CGN to STN   1h20m   491 km   Boeing 737-800 is the type actually flown
  FRA to LHR   1h45m            A320 family, A320neo typical

Fare levels are drawn from what European short-haul actually costs: low-cost
base fares run about 15 to 60 euros one way, a 20 kg bag adds roughly 19 to 60,
and legacy short-haul sits near 90 to 180 with a bag in the fare.

WHAT IS INVENTED
----------------
Every airline. Fineair, Bizzair, Altair, Halcyon, Nordvel and Kestrel do not
exist, and no real carrier is modelled under its own name or near its livery.
Invented prices beside a real brand get read as that airline's charges.

Flight numbers and the exact fares are invented too. They are arranged so the
ranking genuinely flips between baskets, which real data would not reliably do.
"""

import json
from pathlib import Path

HERE = Path(__file__).parent

# ---------------------------------------------------------------- the world

# Summer offsets, so the block time and the clock agree.
PAIRS = {
    "CGN-STN": {"off_from": "+02:00", "off_to": "+01:00", "block": 80},
    "FRA-LHR": {"off_from": "+02:00", "off_to": "+01:00", "block": 105},
    "BER-LGW": {"off_from": "+02:00", "off_to": "+01:00", "block": 115},
    "MUC-DUB": {"off_from": "+02:00", "off_to": "+01:00", "block": 154},
    "BER-BCN": {"off_from": "+02:00", "off_to": "+02:00", "block": 162},
    "VIE-MAD": {"off_from": "+02:00", "off_to": "+02:00", "block": 189},
    "AMS-LIS": {"off_from": "+02:00", "off_to": "+01:00", "block": 190},
}


def _both_ways():
    """Every pair in both directions, because a return trip needs the way back."""
    out = {}
    for pair, spec in PAIRS.items():
        a, b = pair.split("-")
        out["%s-%s" % (a, b)] = dict(spec, **{"from": a, "to": b})
        out["%s-%s" % (b, a)] = dict(
            spec, **{"from": b, "to": a,
                     "off_from": spec["off_to"], "off_to": spec["off_from"]})
    return out


ROUTES = _both_ways()

# A hub each long route can plausibly connect through, and a carrier that does
# not fly it direct. The airports and the hub are real; the routing is invented,
# exactly like the airlines. Nobody actually sells Vienna to Madrid via
# Frankfurt on a carrier that does not exist.
CONNECTIONS = {
    "VIE-MAD": ("FRA", "NV", "3620", 75),
    "MAD-VIE": ("FRA", "NV", "3621", 75),
    "AMS-LIS": ("MAD", "KE", "740", 90),
    "LIS-AMS": ("MAD", "KE", "741", 90),
    "MUC-DUB": ("LHR", "NV", "3540", 65),
    "DUB-MUC": ("LHR", "NV", "3541", 65),
}

# Weekly, three months of them.
import datetime as _dt
DATES = [(_dt.date(2026, 9, 1) + _dt.timedelta(weeks=w)).isoformat() for w in range(13)]

CARRIERS = {
    "FE": {"name": "Fineair",  "equip": "738", "type": "Boeing 737-800"},
    "BZ": {"name": "Bizzair",  "equip": "320", "type": "Airbus A320"},
    "AL": {"name": "Altair",   "equip": "32N", "type": "Airbus A320neo"},
    "HY": {"name": "Halcyon",  "equip": "32Q", "type": "Airbus A321neo"},
    "NV": {"name": "Nordvel",  "equip": "295", "type": "Embraer E195-E2"},
    "KE": {"name": "Kestrel",  "equip": "7M8", "type": "Boeing 737 MAX 8"},
}

# Which type each carrier puts on which length of route. Real types, invented
# carriers: a 737-800 on a short European hop is a fact about the hop, not a
# claim about anybody's fares.
FLEET = {
    "FE": [("738", "Boeing 737-800")],
    "BZ": [("320", "Airbus A320")],
    "AL": [("32N", "Airbus A320neo"), ("32Q", "Airbus A321neo")],
    "HY": [("32Q", "Airbus A321neo")],
    "NV": [("295", "Embraer E195-E2")],
    "KE": [("7M8", "Boeing 737 MAX 8")],
}


def equipment(code, block):
    """The bigger option once a route is long enough to want it."""
    options = FLEET[code]
    return options[-1] if block > 150 and len(options) > 1 else options[0]


def price_on(base, date, block):
    """
    What the same seat costs on a different day.

    Fares move, and a demo where every date is identical makes the date picker
    decoration. Weekends carry a premium, midweek is the trough, and longer
    routes cost more per seat. Deterministic rather than random, so the fixtures
    regenerate byte for byte and the staleness test stays meaningful.
    """
    day = _dt.date.fromisoformat(date)
    weekend = 1.18 if day.weekday() in (4, 6) else 1.0        # Friday and Sunday
    midweek = 0.92 if day.weekday() in (1, 2) else 1.0         # Tuesday, Wednesday
    season = 1.0 + 0.06 * ((day.isocalendar().week % 5) - 2) / 2
    distance = 1.0 + max(0, block - 80) / 260
    return max(900, round(base * weekend * midweek * season * distance))

# base is the headline fare in cents. bag is the checked bag, 0 when included.
# fee is the card fee, charged whether or not anybody asked for it.
# Who flies where, and at what time. One row per flight, repeated on every
# recorded date, which is how a real schedule works: the same wave of departures
# most days, at prices that move.
#
#      carrier, number, departure, base fare in cents, checked bag, card fee
SHORT = [
    ("FE", "1108", "06:35", 1299, 4650, 450),
    ("BZ", "722",  "11:20", 1999, 2400, 0),
    ("AL", "412",  "07:00", 8900, None, 0),
    ("HY", "204",  "09:45", 13400, None, 0),
    ("NV", "3310", "12:55", 6400, 1900, 0),
    ("KE", "881",  "20:15", 4950, 2600, 0),
    ("FE", "1142", "14:10", 2249, 3200, 450),
    ("BZ", "508",  "16:45", 1799, 2900, 0),
    ("AL", "486",  "18:30", 11200, 0,   0),
]

# Longer routes are thinner: fewer daily departures, dearer seats, and the
# deep-discount carrier does not always bother.
LONG = [
    ("BZ", "914",  "08:15", 3199, 2400, 0),
    ("AL", "902",  "06:50", 10400, None, 0),
    ("HY", "118",  "10:05", 15600, None, 0),
    ("NV", "3402", "15:40", 7900, 1900, 0),
    ("KE", "612",  "19:55", 5900, 2600, 0),
    ("FE", "2204", "13:35", 2499, 4650, 450),
    ("AL", "938",  "17:20", 12900, None, 0),
]


def schedule(route):
    return SHORT if ROUTES[route]["block"] <= 120 else LONG


DIRECT = {"FE": "fineair", "BZ": "bizzair"}
AGGREGATED = {"AL", "HY", "NV", "KE"}
RESOLD = {"FE": 118, "BZ": 110, "AL": 106, "NV": 104}   # percent of the base

# One quote in the agent's inventory has already lapsed on every date. Dropping
# it is correct and completely invisible, which is why the search counts drops.
LAPSED_NUMBER = "508"


# ------------------------------------------------------------------ helpers

def minutes(hhmm):
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def clock(total):
    return "%02d:%02d" % ((total // 60) % 24, total % 60)


def arrival_local(route, dep):
    """Departure plus block time, then shifted into the arrival airport's clock."""
    r = ROUTES[route]
    shift = int(r["off_to"][:3]) - int(r["off_from"][:3])
    return clock(minutes(dep) + r["block"] + shift * 60)


def epoch_ms(route, date, hhmm, at_destination=False):
    r = ROUTES[route]
    off = r["off_to"] if at_destination else r["off_from"]
    return int(_dt.datetime.fromisoformat("%sT%s:00%s" % (date, hhmm, off)).timestamp() * 1000)


def hold(date):
    """Quotes hold until a quarter past six on the morning of travel."""
    return "%sT06:15:00Z" % date


# ------------------------------------------------------------------ writers

def connecting(route, date):
    """A two-leg offer through a hub, when the route has one declared.

    Real metasearch results are mostly not direct, and an itinerary with one
    leg exercises none of the interesting arithmetic: the journey time has to
    span the wait, the dedup key has to cover both flights, and the row has to
    say where you change. `Itinerary` has handled all of that since PR #1 and no
    fixture ever used it.
    """
    if route not in CONNECTIONS:
        return None

    hub, code, number, wait = CONNECTIONS[route]
    r = ROUTES[route]
    first = round(r["block"] * 0.55)
    second = round(r["block"] * 0.6)

    dep = "07:30"
    hub_in = clock(minutes(dep) + first)
    hub_out = clock(minutes(hub_in) + wait)
    arrive = clock(minutes(hub_out) + second + (int(r["off_to"][:3]) - int(r["off_from"][:3])) * 60)

    equip, _ = equipment(code, first)
    fare = price_on(5400, date, r["block"])

    return {
        "type": "flight-offer", "id": "c1", "source": "GDS",
        "itineraries": [{
            "duration": "PT%dH%02dM" % ((first + wait + second) // 60, (first + wait + second) % 60),
            "segments": [
                {"departure": {"iataCode": r["from"], "at": "%sT%s:00" % (date, dep)},
                 "arrival": {"iataCode": hub, "at": "%sT%s:00" % (date, hub_in)},
                 "carrierCode": code, "number": number, "id": "1", "numberOfStops": 0,
                 "aircraft": {"code": equip}},
                {"departure": {"iataCode": hub, "at": "%sT%s:00" % (date, hub_out)},
                 "arrival": {"iataCode": r["to"], "at": "%sT%s:00" % (date, arrive)},
                 "carrierCode": code, "number": str(int(number) + 40), "id": "2",
                 "numberOfStops": 0, "aircraft": {"code": equip}},
            ],
        }],
        "price": {"currency": "EUR", "total": "%.2f" % (fare / 100),
                  "grandTotal": "%.2f" % (fare / 100),
                  "base": "%.2f" % (round(fare * 0.7) / 100)},
        "travelerPricings": [{
            "travelerId": "1", "fareOption": "STANDARD", "travelerType": "ADULT",
            "price": {"currency": "EUR", "total": "%.2f" % (fare / 100),
                      "base": "%.2f" % (round(fare * 0.7) / 100)},
            "fareDetailsBySegment": [
                {"segmentId": "1", "cabin": "ECONOMY", "class": "M",
                 "includedCheckedBags": {"quantity": 1}},
                {"segmentId": "2", "cabin": "ECONOMY", "class": "M",
                 "includedCheckedBags": {"quantity": 1}},
            ],
        }],
    }


def amadeus(route, date):
    """The GDS aggregator. Local times with no offset, prices for the booking."""
    r = ROUTES[route]
    data = []
    for i, (code, number, dep, base, bag, _fee) in enumerate(
            f for f in schedule(route) if f[0] in AGGREGATED):
        fare = price_on(base, date, r["block"])
        equip, _ = equipment(code, r["block"])
        data.append({
            "type": "flight-offer", "id": str(i + 1), "source": "GDS",
            "itineraries": [{
                "duration": "PT%dH%02dM" % (r["block"] // 60, r["block"] % 60),
                "segments": [{
                    "departure": {"iataCode": r["from"], "at": "%sT%s:00" % (date, dep)},
                    "arrival": {"iataCode": r["to"],
                                "at": "%sT%s:00" % (date, arrival_local(route, dep))},
                    "carrierCode": code, "number": number, "id": "1", "numberOfStops": 0,
                    "aircraft": {"code": equip},
                }],
            }],
            "price": {"currency": "EUR", "total": "%.2f" % (fare / 100),
                      "grandTotal": "%.2f" % (fare / 100),
                      "base": "%.2f" % (round(fare * 0.7) / 100)},
            "travelerPricings": [{
                "travelerId": "1", "fareOption": "STANDARD", "travelerType": "ADULT",
                "price": {"currency": "EUR", "total": "%.2f" % (fare / 100),
                          "base": "%.2f" % (round(fare * 0.7) / 100)},
                "fareDetailsBySegment": [{
                    "segmentId": "1", "cabin": "ECONOMY", "class": "M",
                    "includedCheckedBags": {"quantity": 0 if bag == 0 else 1},
                }],
            }],
        })

    hop = connecting(route, date)
    if hop:
        data.append(hop)

    return {"meta": {"count": len(data)}, "data": data}


def lowcost(route, date, carrier):
    """A budget carrier selling direct. Offsets present, extras priced beside."""
    r = ROUTES[route]
    flights = []
    for code, number, dep, base, bag, fee in schedule(route):
        if code != carrier:
            continue
        _, name = equipment(code, r["block"])
        flights.append({
            "currency": "EUR",
            "fare": {"amount": round(price_on(base, date, r["block"]) / 100, 2), "type": "value"},
            "holdsUntil": hold(date),
            "legs": [{
                "airline": code, "flightNo": number, "from": r["from"], "to": r["to"],
                "departsAt": "%sT%s:00%s" % (date, dep, r["off_from"]),
                "arrivesAt": "%sT%s:00%s" % (date, arrival_local(route, dep), r["off_to"]),
                "aircraft": name,
            }],
            "extras": {"cabinBag": 0, "checkedBag": round(bag / 100, 2),
                       "seat": 9 if code == "FE" else 7.5, "paymentFee": round(fee / 100, 2)},
        })
    return {"currency": "EUR", "flights": flights}


def reseller(route, date):
    """The agent. Epoch milliseconds, minor units, a markup on everything."""
    r = ROUTES[route]
    results = []
    for code, number, dep, base, bag, _fee in schedule(route):
        if code not in RESOLD:
            continue
        _, name = equipment(code, r["block"])
        included = ["CABIN"]
        buyable = []
        if bag == 0:
            included.append("CHECKED")
        elif bag is not None:
            buyable.append({"type": "CHECKED", "priceMinor": bag + 600})

        results.append({
            "seller": "voyago", "operatedBy": code, "flightNumber": number,
            "route": {"from": r["from"], "to": r["to"]},
            "departEpochMs": epoch_ms(route, date, dep),
            "arriveEpochMs": epoch_ms(route, date, arrival_local(route, dep), True),
            "equipment": name,
            "pricing": {"currencyCode": "EUR",
                        "totalMinor": round(price_on(base, date, r["block"]) * RESOLD[code] / 100),
                        "perPassenger": True},
            "baggage": {"included": included, "buyable": buyable},
            "fees": [] if code != "BZ" else [{"kind": "PAYMENT", "priceMinor": 199}],
            # The lapsed one: held until half past three, quoted at four.
            "holdsUntilEpochMs": (
                int(_dt.datetime.fromisoformat("%sT03:30:00+00:00" % date).timestamp() * 1000)
                if number == LAPSED_NUMBER
                else int(_dt.datetime.fromisoformat("%sT08:15:00+00:00" % date).timestamp() * 1000)),
        })
    return {"results": results}


def main():
    written = 0
    for route in ROUTES:
        for date in DATES:
            slug = "%s-%s" % (route.lower(), date)
            files = {
                "openfare-%s.json" % slug: amadeus(route, date),
                "fineair-%s.json" % slug: lowcost(route, date, "FE"),
                "bizzair-%s.json" % slug: lowcost(route, date, "BZ"),
                "voyago-%s.json" % slug: reseller(route, date),
            }
            for name, payload in files.items():
                (HERE / name).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
                written += 1

    print("%d files: %d routes x %d dates x 4 suppliers."
          % (written, len(ROUTES), len(DATES)))
    print("Airlines invented. Routes, block times and aircraft real.")


if __name__ == "__main__":
    main()

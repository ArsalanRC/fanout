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
ROUTES = {
    "CGN-STN": {"from": "CGN", "to": "STN", "off_from": "+02:00", "off_to": "+01:00", "block": 80},
    "FRA-LHR": {"from": "FRA", "to": "LHR", "off_from": "+02:00", "off_to": "+01:00", "block": 105},
}

CARRIERS = {
    "FE": {"name": "Fineair",  "equip": "738", "type": "Boeing 737-800"},
    "BZ": {"name": "Bizzair",  "equip": "320", "type": "Airbus A320"},
    "AL": {"name": "Altair",   "equip": "32N", "type": "Airbus A320neo"},
    "HY": {"name": "Halcyon",  "equip": "32Q", "type": "Airbus A321neo"},
    "NV": {"name": "Nordvel",  "equip": "295", "type": "Embraer E195-E2"},
    "KE": {"name": "Kestrel",  "equip": "7M8", "type": "Boeing 737 MAX 8"},
}

DATE = "2026-09-01"

# base is the headline fare in cents. bag is the checked bag, 0 when included.
# fee is the card fee, charged whether or not anybody asked for it.
FLIGHTS = [
    # route,     carrier, number, dep local, base, cabin, bag,  fee
    ("CGN-STN", "FE", "1108", "06:35", 1299, 0,    4650, 650),
    ("CGN-STN", "FE", "1142", "14:10", 2249, 0,    3200, 650),
    ("CGN-STN", "BZ", "722",  "11:20", 1999, 0,    2400, 0),
    ("CGN-STN", "BZ", "508",  "16:45", 1799, 0,    2900, 0),
    ("CGN-STN", "AL", "412",  "07:00", 8900, None, None, 0),
    ("CGN-STN", "AL", "486",  "18:30", 11200, None, 0,   0),
    ("CGN-STN", "HY", "204",  "09:45", 13400, None, None, 0),
    ("CGN-STN", "NV", "3310", "12:55", 6400, None, 1900, 0),
    ("CGN-STN", "KE", "881",  "20:15", 4950, 0,    2600, 0),

    ("FRA-LHR", "AL", "902",  "06:50", 10400, None, None, 0),
    ("FRA-LHR", "AL", "938",  "17:20", 12900, None, None, 0),
    ("FRA-LHR", "HY", "118",  "10:05", 15600, None, None, 0),
    ("FRA-LHR", "FE", "2204", "13:35", 2499, 0,    4650, 650),
    ("FRA-LHR", "BZ", "914",  "08:15", 3199, 0,    2400, 0),
    ("FRA-LHR", "NV", "3402", "15:40", 7900, None, 1900, 0),
    ("FRA-LHR", "KE", "612",  "19:55", 5900, 0,    2600, 0),
]

# Who sells what. openfare is the aggregator and carries the full-service side;
# the two budget carriers sell direct; voyago resells at a markup.
DIRECT = {"FE": "fineair", "BZ": "bizzair"}
AGGREGATED = {"AL", "HY", "NV", "KE"}
RESOLD = {"FE": 118, "BZ": 110, "AL": 106, "NV": 104}   # percent of the base

HOLD = {"CGN-STN": "2026-09-01T06:15:00Z", "FRA-LHR": "2026-09-01T06:15:00Z"}

# One quote in the reseller's inventory has already lapsed. Dropping it is
# correct and completely invisible, which is why the search counts drops.
LAPSED = ("CGN-STN", "BZ", "508")


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


def epoch_ms(route, hhmm, at_destination=False):
    import datetime
    r = ROUTES[route]
    off = r["off_to"] if at_destination else r["off_from"]
    stamp = "%sT%s:00%s" % (DATE, hhmm, off)
    return int(datetime.datetime.fromisoformat(stamp).timestamp() * 1000)


def for_route(route):
    return [f for f in FLIGHTS if f[0] == route]


# ------------------------------------------------------------------ writers

def amadeus(route):
    """The GDS aggregator. Local times with no offset, prices for the booking."""
    data = []
    for i, (_, code, number, dep, base, _cabin, bag, _fee) in enumerate(
            f for f in for_route(route) if f[1] in AGGREGATED):
        r = ROUTES[route]
        data.append({
            "type": "flight-offer", "id": str(i + 1), "source": "GDS",
            "itineraries": [{
                "duration": "PT%dH%02dM" % (r["block"] // 60, r["block"] % 60),
                "segments": [{
                    "departure": {"iataCode": r["from"], "at": "%sT%s:00" % (DATE, dep)},
                    "arrival": {"iataCode": r["to"], "at": "%sT%s:00" % (DATE, arrival_local(route, dep))},
                    "carrierCode": code, "number": number, "id": "1", "numberOfStops": 0,
                    "aircraft": {"code": CARRIERS[code]["equip"]},
                }],
            }],
            "price": {"currency": "EUR", "total": "%.2f" % (base / 100),
                      "grandTotal": "%.2f" % (base / 100), "base": "%.2f" % (base * 0.7 / 100)},
            "travelerPricings": [{
                "travelerId": "1", "fareOption": "STANDARD", "travelerType": "ADULT",
                "price": {"currency": "EUR", "total": "%.2f" % (base / 100),
                          "base": "%.2f" % (base * 0.7 / 100)},
                "fareDetailsBySegment": [{
                    "segmentId": "1", "cabin": "ECONOMY", "class": "M",
                    "includedCheckedBags": {"quantity": 0 if bag == 0 else 1},
                }],
            }],
        })
    return {"meta": {"count": len(data)}, "data": data}


def lowcost(route, code):
    """A budget carrier selling direct. Offsets present, extras priced beside."""
    flights = []
    for _, c, number, dep, base, cabin, bag, fee in for_route(route):
        if c != code:
            continue
        r = ROUTES[route]
        flights.append({
            "currency": "EUR",
            "fare": {"amount": round(base / 100, 2), "type": "value"},
            "holdsUntil": HOLD[route],
            "legs": [{
                "airline": c, "flightNo": number, "from": r["from"], "to": r["to"],
                "departsAt": "%sT%s:00%s" % (DATE, dep, r["off_from"]),
                "arrivesAt": "%sT%s:00%s" % (DATE, arrival_local(route, dep), r["off_to"]),
                "aircraft": CARRIERS[c]["type"],
            }],
            "extras": {"cabinBag": cabin or 0, "checkedBag": round(bag / 100, 2),
                       "seat": 9 if c == "FE" else 7.5, "paymentFee": round(fee / 100, 2)},
        })
    return {"currency": "EUR", "flights": flights}


def reseller(route):
    """The agent. Epoch milliseconds, minor units, a markup on everything."""
    results = []
    for _, code, number, dep, base, cabin, bag, _fee in for_route(route):
        if code not in RESOLD:
            continue
        r = ROUTES[route]
        lapsed = (route, code, number) == LAPSED
        included = ["CABIN"]
        buyable = []
        if bag == 0:
            included.append("CHECKED")
        elif bag is not None:
            buyable.append({"type": "CHECKED", "priceMinor": bag + 600})

        results.append({
            "seller": "voyago", "operatedBy": code, "flightNumber": number,
            "route": {"from": r["from"], "to": r["to"]},
            "departEpochMs": epoch_ms(route, dep),
            "arriveEpochMs": epoch_ms(route, arrival_local(route, dep), True),
            "equipment": CARRIERS[code]["type"],
            "pricing": {"currencyCode": "EUR",
                        "totalMinor": round(base * RESOLD[code] / 100),
                        "perPassenger": True},
            "baggage": {"included": included, "buyable": buyable},
            "fees": [] if code != "BZ" else [{"kind": "PAYMENT", "priceMinor": 199}],
            "holdsUntilEpochMs": 1788233400000 if lapsed else epoch_ms(route, "08:15"),
        })
    return {"results": results}


def main():
    written = 0
    for route in ROUTES:
        slug = route.lower()
        files = {
            "openfare-%s.json" % slug: amadeus(route),
            "fineair-%s.json" % slug: lowcost(route, "FE"),
            "bizzair-%s.json" % slug: lowcost(route, "BZ"),
            "voyago-%s.json" % slug: reseller(route),
        }
        for name, payload in files.items():
            (HERE / name).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
            written += 1
            print("wrote", name)
    print("\n%d files. Airlines invented, aircraft and block times real." % written)


if __name__ == "__main__":
    main()

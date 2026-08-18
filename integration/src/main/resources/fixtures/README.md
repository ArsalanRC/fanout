# Fixtures

Every file here is a supplier response exactly as a connector would receive it
over the network. Nothing in this directory is a normalised object or a
hand-made Java structure, and that is deliberate: the parser under test is the
same object the live connector uses, so the demo exercises the normalisation
rather than skipping it.

## Provenance, stated rather than implied

Written by `generate.py` in this directory, from one table. Twelve files kept by
hand drift: a departure gets edited in one and not the other, deduplication
quietly stops working, and the demo shows two rows where it showed one.

| Supplier | Shape | Sells |
|---|---|---|
| `openfare-*` | Amadeus Flight Offers Search | The full-service carriers |
| `fineair-*` | Low-cost carrier direct | Fineair only |
| `bizzair-*` | Low-cost carrier direct | Bizzair only |
| `voyago-*` | Reseller | A bit of everything, at a markup |

### What is real

The routes, the block times and the aircraft, checked on 18 August 2026.

| Route | Block time | Aircraft |
|---|---|---|
| CGN to STN | 1h20m, 491 km | Boeing 737-800 |
| FRA to LHR | 1h45m | Airbus A320 family |

Fare levels follow what European short haul actually costs: low-cost base fares
of roughly 15 to 60 euros one way, a 20 kg bag adding about 19 to 60, and legacy
short haul near 90 to 180 with the bag in the fare.

The earlier version of these fixtures modelled DUS to STN as a direct flight.
It is not one. That was caught by looking the route up rather than assuming it.

### What is invented

Every airline. Fineair, Bizzair, Altair, Halcyon, Nordvel and Kestrel do not
exist, and no real carrier is modelled under its own name or near its livery.
Invented prices beside a real brand get read as that airline's charges.

Flight numbers and exact fares are invented too, arranged so the ranking flips
between baskets and one quote has already lapsed. Real data would not reliably
do either.

## Why nothing here is a real recording

There is no free flight-price API left to record from. This was checked rather
than assumed, and the answer changed while this repository was being written.

**Amadeus decommissioned its Self-Service portal on 17 July 2026.** That was the
one free tier carrying real flight offers. What remains is the Enterprise API
Portal, which is a sales conversation and a commercial contract rather than a
signup. Guides and comparison articles published before that date still describe
the free tier as though it exists; they are stale, and following one wastes an
afternoon.

**Kiwi.com's Tequila API is invitation-only** for selected partners as of 2026,
with no self-service registration.

**Duffel's test mode is free** but flies a fictional airline at unrealistic
prices. That is not better than a modelled fixture, it is a modelled fixture
with a signup and a dependency attached.

**European budget airlines publish nothing at all.** Ryanair and Wizz sell
direct and stay out of third-party channels deliberately; that is their
distribution strategy, not an oversight. Travelport and TPConnects carry them
under commercial contracts. Scraping them breaks their terms and is not
something a portfolio repository should contain.

So the fixtures are modelled, and every place they appear says so. The shapes
are real: `openfare-dus-stn.json` follows the documented Amadeus Flight Offers
Search structure, down to local departure times with no offset and prices quoted
for the whole booking, because those are the two details that make normalisation
worth writing.

## They are quoted at a fixed moment

Every fare here is held until `2026-09-01T06:15:00Z`, because a real quote
expires. So the demo judges freshness against `Market.asOf()` rather than the
wall clock, and the modelled market stays live instead of emptying itself on a
date. Anything reaching a real supplier uses the real clock, which is the only
place the question is genuine.

## The client is still real

`AmadeusClient` speaks the actual Amadeus flight-offers protocol: OAuth2 client
credentials, bearer token, the same query parameters. Anyone holding Enterprise
credentials can point it at the API and it works, and `Recorder` will write a
genuine fixture. It is tested against a real HTTP server rather than a mock, so
the request it builds is proven rather than asserted.

It is kept because a connector that cannot reach its supplier is still the
honest shape of the problem, and because the split between "where the bytes come
from" and "what they mean" is the point of the module.

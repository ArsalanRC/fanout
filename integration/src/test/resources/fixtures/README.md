# Fixtures

Every file here is a supplier response exactly as a connector would receive it
over the network. Nothing in this directory is a normalised object or a
hand-made Java structure, and that is deliberate: the parser under test is the
same object the live connector uses, so the demo exercises the normalisation
rather than skipping it.

## Provenance, stated rather than implied

| File | Shape | Where the data comes from |
|---|---|---|
| `altair-dus-stn.json` | Amadeus Flight Offers Search | **Shape real, values modelled** |
| `skyhop-dus-stn.json` | Low-cost carrier direct | **Shape and values modelled** |

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
are real: `altair-dus-stn.json` follows the documented Amadeus Flight Offers
Search structure, down to local departure times with no offset and prices quoted
for the whole booking, because those are the two details that make normalisation
worth writing.

## The client is still real

`AmadeusClient` speaks the actual Amadeus flight-offers protocol: OAuth2 client
credentials, bearer token, the same query parameters. Anyone holding Enterprise
credentials can point it at the API and it works, and `Recorder` will write a
genuine fixture. It is tested against a real HTTP server rather than a mock, so
the request it builds is proven rather than asserted.

It is kept because a connector that cannot reach its supplier is still the
honest shape of the problem, and because the split between "where the bytes come
from" and "what they mean" is the point of the module.

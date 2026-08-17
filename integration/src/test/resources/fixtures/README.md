# Fixtures

Every file here is a supplier response exactly as a connector would receive it
over the network. Nothing in this directory is a normalised object or a
hand-made Java structure, and that is deliberate: the parser under test is the
same object the live connector uses, so the demo exercises the normalisation
rather than skipping it.

## Provenance, stated rather than implied

| File | Shape | Where the data comes from |
|---|---|---|
| `altair-dus-stn.json` | Amadeus Flight Offers Search | **Shape real, values modelled.** Replace with a recorded response once a key is available |
| `skyhop-dus-stn.json` | Low-cost carrier direct | **Shape and values modelled.** No European budget airline publishes an API |

The second row is the honest part. Ryanair, Wizz Air and the rest sell direct
and stay out of third-party channels on purpose, and the routes that do carry
them are commercial contracts rather than free developer keys. So the low-cost
payload is built to price the way those carriers actually price, a low base with
everything else itemised, and it is labelled as modelled everywhere it appears.

## Recording a real one

Run the recorder locally with a key in the environment. The key never enters
this repository, and no file here contains one.

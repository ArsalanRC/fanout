# fanout

**English** · [Deutsch](./README.de.md)

A metasearch asks eight suppliers for the same flight. Give each one a three second timeout and the search can take twenty four seconds. Every timeout in that config is correct. The system is still wrong.

`fanout` is a small metasearch architecture in Java. Two services, one shared deadline, and an answer that says which suppliers it is missing.

Java 21+, **zero runtime dependencies**, 135 tests.

## Run it

Two processes, talking over HTTP.

```bash
mvn -q install -DskipTests

mvn -q -pl integration exec:java \
  -Dexec.mainClass=io.github.arsalanrc.fanout.integration.IntegrationServer &

FANOUT_INTEGRATION_URL=http://127.0.0.1:8081 mvn -q -pl search exec:java \
  -Dexec.mainClass=io.github.arsalanrc.fanout.search.SearchServer &
```

The install matters. `-pl` resolves the sibling modules from your local repository, not from the reactor.

Now search. Düsseldorf to London Stansted, hand luggage only:

```bash
curl 'http://127.0.0.1:8080/search?origin=DUS&destination=STN&date=2026-09-01&basket=cabin'
```

```
complete: true    took: 691 ms

  BZ722   Bizzair    19.99   from bizzair    2 sellers
  FE1108  Fineair    21.49   from fineair    2 sellers
  FE1142  Fineair    28.99   from fineair    1 seller
  AL412   Altair     89.00   from openfare   2 sellers
  AL486   Altair    112.00   from openfare   1 seller
  HY204   Halcyon   134.00   from openfare   1 seller

  openfare  ANSWERED   152 ms   3 fares
  fineair   ANSWERED   119 ms   2 fares
  bizzair   ANSWERED   223 ms   1 fare
  voyago    ANSWERED   678 ms   3 fares
```

Now ask for an answer in 300 milliseconds. One supplier takes 678:

```bash
curl 'http://127.0.0.1:8080/search?...&budget=300'
```

```
complete: false   took: 301 ms

  openfare  ANSWERED    126 ms   3 fares
  fineair   ANSWERED     85 ms   2 fares
  bizzair   ANSWERED    203 ms   1 fare
  voyago    TIMED_OUT   301 ms   0 fares
```

That is the whole argument in two commands. The search came back on time, it returned every row it had, and it said what it was missing.

## What the slow supplier cost

Not the rows. All six journeys are still there and every cheapest price is identical. What went is the second price on three of them:

| Journey | With voyago | Without |
|---|---|---|
| BZ722 | 19.99, spread 3.99 | 19.99, spread 0 |
| FE1108 | 21.49, spread 3.01 | 21.49, spread 0 |
| AL412 | 89.00, spread 5.00 | 89.00, spread 0 |

So the partial answer is still correct about price. It has lost the information about how much shopping around is worth, and `complete: false` is how you know to distrust it.

## The problem this is built around

Six ways a metasearch goes wrong. Four of them fail silently, which is the class worth building for.

**One deadline, not eight timeouts.** The caller's budget is set once, at the edge. Every call downstream gets whatever is left of it, never a fresh allowance. A supplier reached at 1.9 seconds into a 2 second search gets 100 milliseconds.

**Partial results beat no results.** A supplier that misses the deadline is reported beside the results it would have contributed. Failing the whole search because one of eight was slow is the easy mistake and the expensive one.

**Lateness is not breakage.** A dead supplier stops being called and recovers on its own. A slow one keeps being called, because counting slowness as failure drops working suppliers on a busy afternoon.

**Normalisation is where the bugs live.** Suppliers quote per passenger or per booking, in different currencies, with different rounding, in local time with no offset. Comparing the numbers as they arrive compares different things.

**One flight sold by three suppliers is one row.** Three rows look full and hide the spread.

**A lapsed fare is worse than no fare.** It sorts to the top, gets clicked, and fails where somebody was about to pay.

## A fare has no single total

This is the part that changes the answer, and it is why the pricing model is a first class thing here rather than a number on a record.

A budget carrier quotes the seat. A legacy carrier quotes the seat with a cabin bag, a suitcase and a seat assignment already in it. 19.99 and 89.00 are not two prices for the same thing, and a metasearch that sorts on the headline tells the traveller the wrong airline is cheaper.

So `Fare` carries a base and a list of things priced beside it, and the total is a question:

```java
fare.totalFor(Basket.HAND_LUGGAGE_ONLY);   // 21.49 EUR
fare.totalFor(Basket.WITH_CHECKED_BAG);    // 67.99 EUR
```

Three refusals come with it. A payment fee is charged whether or not the basket asked for it. An ancillary the fare does not sell is refused rather than priced at zero. A carrier that will not sell you a suitcase has not made it free. Two currencies inside one fare are refused, because the connector has not finished its job.

## The deadline crosses the wire

Two services is only worth the trouble if the budget survives the hop between them. A deadline that stops at a process boundary is a timeout with extra steps.

```
GET /suppliers/fineair/fares?origin=DUS&destination=STN&date=2026-09-01
X-Fanout-Deadline-Ms: 842
```

Three rules on that header.

**A caller can shorten the budget and can never extend it.** The service starts from its own ceiling and takes whichever is sooner. Ask for ten minutes and you get five seconds. A service that trusts a client's deadline has handed a stranger the right to hold its threads open.

**Late and broken answer differently.** `504` for a supplier that ran out of budget, `502` for one that failed. The client maps `504` back to lateness, so the circuit breaker never counts it. Collapse the two and a slow supplier gets dropped from every future search.

**Money crosses as minor units and a currency code.** Never as a decimal. `21.49` read back as a double and multiplied by 100 is `2148.9999`, and every guard in `Money` would then be defending a number that already lost a cent.

## The modules

```
core          The model. Knows nothing about the network
telemetry     Spans, written as OTLP/JSON. No SDK
integration   Connectors, normalisation, and the supplier-facing service
search        The fan-out, the breaker, and the edge service
```

These are modules rather than packages on purpose. A package can be reached across by accident and a module cannot. The boundary is the thing this repository is arguing for, so it is enforced by the build.

The fan-out runs a virtual thread per supplier. Each one spends its life waiting on a socket, which is what a platform thread is worst at holding, and there is no pool size to tune.

Telemetry writes the OpenTelemetry wire format directly, so a Collector ingests it with no SDK and no dependency. A supplier the breaker skipped still gets a span. A trace showing nothing for it would read as never configured, rather than deliberately not called.

## The data is modelled, and here is why

There is no free flight-price API left. This was checked rather than assumed, and the answer changed while the repository was being written.

Amadeus decommissioned its Self-Service portal on 17 July 2026. That was the one free tier carrying real flight offers. Kiwi's Tequila API is invitation only. Ryanair and Wizz sell direct and stay out of third party channels deliberately, which is their distribution strategy rather than an oversight. Scraping them breaks their terms.

So every airline here is invented. Fineair, Bizzair, Altair and Halcyon do not exist. Modelling a real carrier under its own name would put invented prices beside a real brand.

**What is real is the shape.** The fixtures are raw supplier payloads, not normalised objects, and the three of them disagree on every convention: local time with no offset, an explicit offset, and epoch milliseconds. Decimal strings, decimal numbers, and integer minor units. The connector runs the same parsing code whether the bytes came from a socket or from disk, so the demo cannot drift into being a different code path.

`AmadeusClient` still speaks the real protocol. Point it at Enterprise credentials and `Recorder` writes a genuine fixture. Credentials come from the environment and nothing here reads a key from a file this repository controls.

## Running the tests

```bash
mvn verify
```

135 tests. CI runs them on Java 21 and 25.

The two-service tests are the ones worth reading. They start both servers on real sockets, because running in one JVM hides exactly the failures this is about: a deadline that stops at the boundary, a timeout arriving as a failure, a supplier that drops out of the results while still reporting that it answered.

Three of those shipped as bugs during the build and none of them failed anything:

- A parser dated its expiry from the wall clock, so every recorded fare was lapsed. A quarter of the market vanished from the results while the search still reported itself complete.
- A `504` restored the thread's interrupt flag before writing the body, which throws on an interruptible channel. The caller got a dropped connection, which reads as breakage.
- A cancelled supplier reported `0 ms`, which renders as the fastest one in the list.

## Not built yet

Listed rather than quietly missing:

- Multi-leg and return journeys. The model carries them and no fixture exercises them
- Currency conversion. `Money` refuses to compare across currencies and nothing converts yet
- A results page. The service returns JSON and nothing renders it
- Caching, which is where fare freshness stops being theoretical

## Author

Built by [Arsalan Khadim](https://www.linkedin.com/in/muhammad-arsalan-khadim-b87550259/) · [GitHub](https://github.com/ArsalanRC)

## Licence

MIT

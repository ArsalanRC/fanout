# fanout

[English](./README.md) · **Deutsch**

Eine Metasuche fragt acht Anbieter nach demselben Flug. Gibt man jedem drei Sekunden Timeout, dauert die Suche im schlechtesten Fall vierundzwanzig. Jeder einzelne Timeout ist richtig gesetzt. Das System ist trotzdem kaputt.

`fanout` ist eine kleine Metasuch-Architektur in Java. Zwei Services, ein gemeinsames Zeitbudget, und eine Antwort, die sagt, welche Anbieter fehlen.

Java 21+, **keine Laufzeit-Abhängigkeiten**, 139 Tests.

**[Live ansehen](https://arsalanrc.github.io/fanout/?lang=de)**: der Fan-out in
Echtzeit, mit echten aufgezeichneten Laufzeiten, auf Deutsch und Englisch. Setz
das Budget unter das, was der langsamste Anbieter braucht, und sieh zu wie die
Antwort unvollständig zurückkommt. Ohne Installation.

## Ausprobieren

Zwei Prozesse, die über HTTP miteinander reden.

```bash
mvn -q install -DskipTests

mvn -q -pl integration exec:java \
  -Dexec.mainClass=io.github.arsalanrc.fanout.integration.IntegrationServer &

FANOUT_INTEGRATION_URL=http://127.0.0.1:8081 mvn -q -pl search exec:java \
  -Dexec.mainClass=io.github.arsalanrc.fanout.search.SearchServer &
```

Das `install` ist Pflicht. `-pl` holt die Nachbarmodule aus dem lokalen Repository und nicht aus dem Reactor.

Jetzt suchen. Köln/Bonn nach London Stansted, nur mit Handgepäck:

```bash
curl 'http://127.0.0.1:8080/search?origin=CGN&destination=STN&date=2026-09-01&basket=cabin'
```

```
complete: true    took: 691 ms

  BZ722   Bizzair    19.99   von bizzair    2 Verkäufer
  FE1108  Fineair    21.49   von fineair    2 Verkäufer
  FE1142  Fineair    28.99   von fineair    1 Verkäufer
  AL412   Altair     89.00   von openfare   2 Verkäufer
  AL486   Altair    112.00   von openfare   1 Verkäufer
  HY204   Halcyon   134.00   von openfare   1 Verkäufer

  openfare  ANSWERED   152 ms   3 Tarife
  fineair   ANSWERED   119 ms   2 Tarife
  bizzair   ANSWERED   223 ms   1 Tarif
  voyago    ANSWERED   678 ms   3 Tarife
```

Und jetzt dasselbe in 300 Millisekunden. Ein Anbieter braucht 678:

```bash
curl 'http://127.0.0.1:8080/search?...&budget=300'
```

```
complete: false   took: 301 ms

  openfare  ANSWERED    126 ms   3 Tarife
  fineair   ANSWERED     85 ms   2 Tarife
  bizzair   ANSWERED    203 ms   1 Tarif
  voyago    TIMED_OUT   301 ms   0 Tarife
```

Das ist das ganze Argument in zwei Befehlen. Die Suche war pünktlich, sie hat jede Zeile geliefert die sie hatte, und sie sagt was fehlt.

## Was der langsame Anbieter gekostet hat

Keine einzige Zeile. Alle sechs Verbindungen sind noch da und jeder günstigste Preis ist identisch. Weg ist der zweite Preis bei dreien davon:

| Verbindung | Mit voyago | Ohne |
|---|---|---|
| BZ722 | 19,99, Spanne 3,99 | 19,99, Spanne 0 |
| FE1108 | 21,49, Spanne 3,01 | 21,49, Spanne 0 |
| AL412 | 89,00, Spanne 5,00 | 89,00, Spanne 0 |

Die Teilantwort stimmt also weiterhin beim Preis. Verloren ist die Information, was Vergleichen überhaupt bringt. Genau dafür steht `complete: false` in der Antwort.

## Das Problem dahinter

Sechs Arten, eine Metasuche falsch zu bauen. Vier davon scheitern lautlos, und das ist die interessante Sorte.

**Ein Zeitbudget statt acht Timeouts.** Das Budget wird einmal gesetzt, ganz außen. Jeder Aufruf weiter unten bekommt nur den Rest davon, nie eine frische Zuteilung. Ein Anbieter, der nach 1,9 von 2 Sekunden dran ist, bekommt 100 Millisekunden.

**Eine Teilantwort schlägt keine Antwort.** Ein Anbieter, der das Budget reißt, steht neben den Ergebnissen die er beigetragen hätte. Die ganze Suche scheitern zu lassen, weil einer von acht langsam war, ist der naheliegende Fehler.

**Langsam ist nicht kaputt.** Ein toter Anbieter wird nicht mehr angefragt und erholt sich von selbst. Ein langsamer wird weiter angefragt. Zählt man Langsamkeit als Fehler, fliegt an einem vollen Nachmittag ein funktionierender Anbieter raus.

**In der Normalisierung stecken die Bugs.** Anbieter rechnen pro Person oder pro Buchung, in verschiedenen Währungen, mit verschiedener Rundung, in Ortszeit ohne Offset. Wer die Zahlen so vergleicht wie sie ankommen, vergleicht verschiedene Dinge.

**Ein Flug bei drei Anbietern ist eine Zeile.** Drei Zeilen sehen voll aus und verstecken die Preisspanne.

**Ein abgelaufener Tarif ist schlimmer als keiner.** Er sortiert sich nach oben, wird angeklickt, und scheitert genau dort wo jemand zahlen wollte.

## Ein Tarif hat keinen einzelnen Gesamtpreis

Das ist der Teil, der die Antwort verändert. Deshalb ist das Preismodell hier ein eigener Typ und keine Zahl auf einem Record.

Ein Billigflieger verkauft den Sitzplatz. Eine Linienairline verkauft den Sitzplatz mit Handgepäck, Koffer und Sitzplatzwahl darin. Was kostet der Flug also? Ohne Warenkorb hat die Frage keine Antwort. 19,99 und 89,00 sind keine zwei Preise für dieselbe Sache, und eine Metasuche, die nach dem Schaufensterpreis sortiert, nennt dem Reisenden die falsche Airline als günstigste.

`Fare` trägt deshalb einen Grundpreis und eine Liste dessen, was daneben kostet. Der Gesamtpreis ist eine Frage:

```java
fare.totalFor(Basket.HAND_LUGGAGE_ONLY);   // 21.49 EUR
fare.totalFor(Basket.WITH_CHECKED_BAG);    // 67.99 EUR
```

Dazu kommen drei Verweigerungen. Eine Zahlungsgebühr fällt an, egal ob der Warenkorb danach gefragt hat. Eine Leistung, die der Tarif nicht verkauft, wird abgelehnt statt mit null bepreist. Eine Airline, die keinen Koffer verkauft, hat ihn nicht verschenkt. Und zwei Währungen in einem Tarif werden abgelehnt, weil der Connector dann nicht fertig ist.

## Das Zeitbudget überquert die Leitung

Zwei Services lohnen sich nur, wenn das Budget den Sprung dazwischen übersteht. Ein Budget, das an der Prozessgrenze endet, ist ein Timeout mit Umweg.

```
GET /suppliers/fineair/fares?origin=CGN&destination=STN&date=2026-09-01
X-Fanout-Deadline-Ms: 842
```

Für diesen Header gelten drei Regeln.

**Der Aufrufer darf kürzen und niemals verlängern.** Der Service startet bei seiner eigenen Obergrenze und nimmt den früheren der beiden Zeitpunkte. Wer zehn Minuten verlangt, bekommt fünf Sekunden. Ein Service, der dem Budget seines Clients vertraut, gibt Fremden das Recht, seine Threads zu belegen.

**Zu spät und kaputt antworten unterschiedlich.** `504` für einen Anbieter, dem das Budget ausging, `502` für einen der gescheitert ist. Der Client übersetzt `504` zurück in Verspätung, damit der Circuit Breaker sie nicht mitzählt. Wirft man beides zusammen, fliegt ein langsamer Anbieter aus allen künftigen Suchen.

**Geld reist in Minor Units mit Währungscode.** Nie als Dezimalzahl. `21.49` als Double zurückgelesen und mal 100 ergibt `2148.9999`. Jede Prüfung in `Money` würde dann eine Zahl verteidigen, die längst einen Cent verloren hat.

## Die Module

```
core          Das Modell. Weiß nichts vom Netzwerk
telemetry     Spans als OTLP/JSON. Ohne SDK
integration   Connectoren, Normalisierung, und der Anbieter-Service
search        Der Fan-out, der Breaker, und der Edge-Service
```

Das sind bewusst Module und keine Packages. In ein Package greift man versehentlich hinein, in ein Modul nicht. Die Grenze ist das eigentliche Argument dieses Repositories, also erzwingt der Build sie.

Der Fan-out fährt einen Virtual Thread pro Anbieter. Jeder davon wartet die ganze Zeit auf einen Socket, und genau darin ist ein Plattform-Thread am schlechtesten. Eine Poolgröße gibt es nicht zu tunen.

Die Telemetrie schreibt direkt das OpenTelemetry-Wire-Format. Ein Collector liest das ohne SDK und ohne Abhängigkeit. Auch ein vom Breaker übersprungener Anbieter bekommt einen Span. Ein Trace ohne Eintrag würde sonst wie "nie konfiguriert" aussehen statt wie "absichtlich nicht angefragt".

## Die Daten sind modelliert, und zwar deshalb

Es gibt keine kostenlose Flugpreis-API mehr. Das wurde geprüft und nicht vermutet, und die Antwort hat sich während der Arbeit an diesem Repository geändert.

Amadeus hat sein Self-Service-Portal am 17. Juli 2026 abgeschaltet (nachgelesen beim Anbieter selbst, nicht in einem Vergleichsartikel). Das war der einzige kostenlose Zugang mit echten Flugangeboten. Die Tequila-API von Kiwi läuft nur auf Einladung. Ryanair und Wizz verkaufen direkt und halten sich bewusst aus fremden Kanälen heraus. Das ist ihre Vertriebsstrategie und kein Versehen. Scraping verstößt gegen ihre Bedingungen.

Jede Airline hier ist deshalb erfunden. Fineair, Bizzair, Altair und Halcyon gibt es nicht. Eine echte Airline unter ihrem eigenen Namen zu modellieren würde erfundene Preise neben eine echte Marke stellen.

**Echt ist die Form.** Die Fixtures sind rohe Anbieter-Antworten, keine normalisierten Objekte. Alle drei widersprechen sich in jeder Konvention: Ortszeit ohne Offset, expliziter Offset, und Epoch-Millisekunden. Dezimalstrings, Dezimalzahlen, und ganzzahlige Minor Units. Der Connector fährt denselben Parser, egal ob die Bytes vom Socket kommen oder von der Platte. Die Demo kann also nicht heimlich zu einem anderen Codepfad werden.

`AmadeusClient` spricht weiterhin das echte Protokoll. Mit Enterprise-Zugangsdaten schreibt `Recorder` eine echte Fixture. Zugangsdaten kommen aus der Umgebung, und nichts hier liest einen Schlüssel aus einer Datei dieses Repositories.

## Tests laufen lassen

```bash
mvn verify
```

135 Tests. CI fährt sie auf Java 21 und 25.

Lesenswert sind die Tests über beide Services. Sie starten beide Server auf echten Sockets, denn ein einzelner JVM versteckt genau die Fehler um die es geht: ein Budget das an der Grenze endet, ein Timeout das als Fehler ankommt, ein Anbieter der aus den Ergebnissen fällt und trotzdem meldet dass er geantwortet hat.

Drei davon waren beim Bauen echte Bugs, und keiner hat irgendetwas rot gemacht:

- Ein Parser datierte seine Gültigkeit von der Uhrzeit des Rechners. Jeder aufgezeichnete Tarif war damit abgelaufen. Ein Viertel des Marktes verschwand aus den Ergebnissen, während die Suche sich weiter als vollständig meldete.
- Ein `504` setzte das Interrupt-Flag des Threads, bevor der Body geschrieben war. Auf einem unterbrechbaren Channel wirft das. Der Aufrufer bekam einen Verbindungsabbruch, und der liest sich wie ein Defekt.
- Ein abgebrochener Anbieter meldete `0 ms`. Auf einer Seite steht er damit als der schnellste von allen.

## Noch nicht gebaut

Bewusst aufgelistet statt still weggelassen:

- Umsteigeverbindungen und Rückflüge. Das Modell kann sie, keine Fixture nutzt sie
- Währungsumrechnung. `Money` verweigert den Vergleich über Währungen und nichts rechnet um
- Eine Ergebnisseite. Der Service liefert JSON und niemand rendert es
- Caching, wo die Frage nach der Tarif-Gültigkeit konkret wird

## Autor

Gebaut von [Arsalan Khadim](https://www.linkedin.com/in/muhammad-arsalan-khadim-b87550259/) · [GitHub](https://github.com/ArsalanRC)

## Lizenz

MIT

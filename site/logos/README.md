# Carrier marks

Four airlines, four brands, and none of them exist.

Fineair, Bizzair, Altair and Halcyon are invented, the same way the fares are.
No real carrier is modelled here under its own name or anything close to its
livery. That rule is the reason the market is fictional in the first place.
Invented prices sitting beside a real brand get read as that airline's charges
sooner or later.

## The set

| | Code | Position | Ground | Mark |
|---|---|---|---|---|
| Fineair | `FE` | Deep discount | `#E23A11` | Three swept bars |
| Bizzair | `BZ` | Budget | `#0E9E8F` | A climbing dart |
| Altair | `AL` | Full service | `#12294A` | A star in a ring |
| Halcyon | `HY` | Premium | `#0C3A31` | An H whose crossbar lifts |

Accents: Altair and Halcyon carry gold `#C9A227` and champagne `#D8C08A`. The
other two are white on their ground.

The four are meant to be told apart at a glance in a results row, because that
is the only place they ever appear together. So they differ by silhouette and
not only by colour: bars, a triangle, a star, a letter.

## Why each one looks like that

The brand follows the pricing model rather than being decoration on top of it.

**Fineair** shouts. It is the carrier with the tiny headline fare, the dear
bags and a payment fee, so the mark is loud, red and italic. That is how a
seller competing purely on the first number presents itself.

**Bizzair** is the honest budget one. Low base, moderate bags, no payment fee.
Rounded and friendly, and deliberately not aggressive, because the difference
from Fineair is the whole point of having both.

**Altair** is the flag carrier. Navy and gold, wide tracking, a star. Altair is
the brightest star in Aquila, so the name does the work.

**Halcyon** is the top of the market and never raises its voice. Deep green,
champagne, a light monogram. A halcyon is the kingfisher of the myth that calms
the sea, which is where the colour comes from.

## Files

Each carrier has a lockup and a square mark:

```
fineair.svg        264 x 64, mark plus wordmark
fineair-mark.svg    64 x 64, mark alone
```

Use the mark in results rows and anywhere under about 120px wide. The wordmark
is set in `<text>`, so it resolves against the system font stack rather than
shipping a webfont.

## Three things learned drawing them

Worth writing down, because all three were only visible once rendered.

**A thin even stroke has no silhouette.** Bizzair started as a bird drawn in
one line. At 22px it read as a squiggle. A filled shape survives; an outline
does not.

**A smooth closed curve is a leaf.** Halcyon was a kingfisher wing twice, and
both times it read as a plant. Without a shaft or a notch there is nothing to
tell a wing from a leaf, so it became a monogram instead.

**A bare four-point star is the sparkle emoji.** Altair needed the ring, which
gives it mass below 20px and moves it toward a compass rose.

Every one of those was caught by rendering the sheet and looking at it. None of
them is visible in the SVG source.

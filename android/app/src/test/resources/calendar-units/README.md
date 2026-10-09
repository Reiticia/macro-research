# Calendar unit regression samples

`forex-factory.json` contains synthetic formatted-number cases, not an API contract.

`tradingview.json` is a field projection of the public JSON response fetched on 2026-10-09 from:

`https://economic-calendar.tradingview.com/events?from=2026-09-01T00%3A00%3A00Z&to=2026-09-30T23%3A59%3A59Z&countries=US`

The response supplies display-scaled `actual`, `previous`, `forecast`, separate `scale` (K/M/B), optional `unit`, and absolute `*Raw` values. Both clients retain display values and combine the explicit scale and unit in the existing unit string (e.g. K, M, B $). Do not consume `*Raw` while keeping a K/M/B label; that would scale twice. This is observed response behavior, not a claim that TradingView publishes a stable documented API contract.

The source descriptions for `ECONOMICS:USBCOI` (ISM Manufacturing), `ECONOMICS:USNMPMI` (ISM Services) and `ECONOMICS:USCPMI` (Chicago PMI) explicitly describe diffusion indices and their 50-point thresholds. These exact US tickers may use `index points` when both scale and unit are absent. No generic Business Confidence/title matching is used and no employment/inventory magnitude is guessed.

Do not infer currency from the calendar row's `currency` alone: non-monetary US releases also have `currency=USD`. The Current Account sample has an explicit `$` unit plus B scale; the dollar symbol is preserved without assigning a dollar subtype.

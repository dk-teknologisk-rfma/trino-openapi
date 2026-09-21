---
status: accepted
---

# One seam owns both the Trino type and the OpenAPI serialisation style of a parameter

`OpenApiClient.getFilter(OpenApiColumn, TupleDomain)` used to do double duty:
it extracted the predicate's value out of a Trino `Domain` *and* rendered some
of those values to wire text (decimals via `NumberFormat`, dates and
timestamps via the schema's format), while leaving others (`Long`, `Double`,
`Block`, `SqlMap`, `SqlRow`) as raw Trino-typed values. Four different
consumers - query params, header params, path params, and the predicate
columns echoed back into result rows - each took whatever `getFilter`
happened to hand them and did their own ad hoc thing with it. Query params
ran the value through `encodeQueryParamValue`, which only knew how to handle
`Block` by assuming every array was `VARCHAR` (so `array(decimal(18,8))`
predicates threw `ClassCastException`). Header and path params called
`.toString()` directly, which is correct for a `Long` and wrong for anything
that needed real rendering. And the echoed-result path put whatever
`getFilter` returned straight into a result row - meaning a decimal predicate
column's row value was a rendered `String`, not the `Long`/`Int128` that
`InMemoryRecordSet` actually requires for a `DecimalType` column.

None of `style` or `explode` - the OpenAPI keywords that say how a
non-scalar parameter is supposed to be serialised onto the wire - were read
anywhere. Arrays always rendered as repeated parameters, regardless of what
the spec declared.

## Decision

`getFilter` now only extracts: it returns the Trino-typed value (`Long`,
`Double`, `Int128`, `Slice`-derived `String` for varchar, `Block`, `SqlMap`,
`SqlRow`) and nothing else. It performs no formatting.

A single new function, `OpenApiClient.encodeParam(OpenApiColumn, ParameterLocation,
Object typedValue)`, is the only place that turns a typed value into wire
text, for query, header *and* path parameters alike:

- Decimals are rendered via `BigDecimal` built directly from the unscaled
  `Long`/`Int128` and the type's scale, never via `double` - this is what
  makes the rendering exact, locale-independent, and free of grouping
  separators.
- `double`/`real` values go through `BigDecimal.valueOf(d).toPlainString()`
  rather than `Double.toString`, which would emit scientific notation
  (`1.0E10`) that most APIs reject.
- Arrays read `style` and `explode` off the column (carried there from the
  OpenAPI `Parameter` object at spec-parse time, in `OpenApiSpec` - not
  re-parsed from the spec on every request) and implement `form` (both
  explode modes) and `simple`. Any other style throws, naming the style and
  the parameter, rather than guessing at a serialisation nobody asked for.
- Map and row values throw a clear `TrinoException` naming the parameter,
  rather than serialising a Java object's `toString()` onto the wire.

The echoed-result path (`getFilterValues(table, httpPath, null)`, consumed
when building result rows) keeps using the raw typed value from `getFilter`
directly - it never calls `encodeParam`. It was never supposed to render
anything; it was only ever broken because `getFilter` used to render for
*every* consumer indiscriminately.

## Considered options

**Keep rendering inside `getFilter`, and just fix each of decimal/date/array
independently at their existing call sites.** Rejected: the four consumers
would keep diverging - there's no guarantee a future array bugfix at the
query-param call site would also get applied at the header or path call
sites, exactly as happened here. A single seam makes "how do I render a
parameter for the wire" answerable by reading one function.

**Route the encoder through `JsonTrinoConverter.convert(Block, ...)`.**
Rejected: that function's job is producing JSON (`ObjectNode`/`ArrayNode`/
`RawValue`) for request bodies, not query-string text. Small primitive
rendering could theoretically be shared, but the entry points stay separate
on purpose - conflating "serialise to JSON" with "serialise to a query
string" is exactly the kind of double duty this ADR is about eliminating, not
reintroducing one level up.

## Consequences

This seam is hard to reverse: every predicate value that reaches the wire -
for every silo this connector talks to - now flows through it. A defect here
affects every table with a query, header, or path predicate, which is also
why it comes with the encoding-level test coverage it does (see
`OpenApiClientTest`) rather than relying solely on end-to-end query tests.

Carrying `style`/`explode` onto `OpenApiColumn` means two parameters that get
merged into the same predicate column (see `mergeColumns` in `OpenApiSpec`)
share one style/explode pair - whichever the first-encountered parameter
declared. This hasn't been a problem in the six specs this connector meets,
but a future spec where genuinely different parameters share a column name
and disagree on `style` would silently pick one. That's a narrower version of
a pre-existing limitation (predicate columns already assume merged parameters
agree on type), not a new one.

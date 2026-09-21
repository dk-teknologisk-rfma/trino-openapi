# Context

Glossary for the trino-openapi connector. Terms are defined as the domain uses
them, not as the code spells them.

## spec

An OpenAPI document, version 3.0 or 3.1, that describes an HTTP API. A catalog
is configured with exactly one spec, which is read once when the catalog is
created. The spec is the sole source of truth for which tables exist.

## path

A single endpoint template in the spec, such as `/orgs/{org}`. A path carries
one operation per HTTP method. Paths whose parameters differ are merged into a
single table when they share a base path.

## table

The SQL surface of one or more paths that return JSON. Tables are derived from
the spec at catalog creation; there is no separate catalog of tables and no way
to declare one by hand. A spec that yields no tables - because it declares no
paths, or because none of its paths qualify (no JSON 200 response, or an
identifier that resolves to an empty name) - produces a catalog with zero
tables rather than a load failure; the reason is logged, not thrown.

## components

The reusable-definitions block of a spec. It holds `schemas`, `securitySchemes`
and other reusable objects. The whole block is optional: a spec that inlines
everything and declares no authentication has no components at all.

## securitySchemes

The map inside `components` naming the authentication mechanisms an API
offers - what each mechanism *is*. Declaring a scheme does not apply it.

## security requirement

A reference to one or more declared schemes, stating which authentication an
operation *demands*. Requirements appear either at the root of the spec, where
they apply to every operation, or on an individual operation, where they
override the root. A requirement naming a scheme that `securitySchemes` does
not define is unsatisfiable, and the connector moves on to the next requirement
rather than failing - but if every requirement for a request turns out to be
unsatisfiable, the request is refused rather than sent without credentials. See
[ADR 0001](docs/adr/0001-unsatisfiable-security-requirements.md).

The three terms are routinely conflated. `components` is the container,
`securitySchemes` is one map within it, and a security requirement is a
reference to an entry in that map. Each can be absent independently.

## parameter

A spec-declared input to an operation: a query, path or header parameter, or a
request body field. Distinct from a predicate column, which is how that input
is exposed in SQL.

## predicate column

The SQL surface of a parameter. A `WHERE` clause on a predicate column supplies
the value that becomes a request parameter (or body field) when the connector
calls the API; it is not a column of returned data, even though some predicate
columns are also echoed back into the result row.

## parameter style

How a non-scalar parameter (an array, mostly) is rendered onto the wire, per
the OpenAPI `style` and `explode` keywords on the parameter object - for
example, one comma-joined query parameter versus several repeated ones with
the same name. Style and explode are properties of the parameter, not of its
schema, and their defaults differ by location. See
[ADR 0002](docs/adr/0002-parameter-rendering-seam.md).

## typed value vs wire value

The typed value is what a predicate column's `WHERE` clause resolves to,
extracted from a Trino `Domain` as the column's Trino type (`Long`, `Slice`,
`Int128`, `Block`, ...). The wire value is that same value rendered as the
text that actually goes on the HTTP request - a query string fragment, a
header value, a path segment. The two used to be conflated in one function;
keeping them separate is the subject of
[ADR 0002](docs/adr/0002-parameter-rendering-seam.md).

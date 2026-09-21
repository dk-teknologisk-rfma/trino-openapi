# Provenance: dmi-metobs.json, dmi-climateData.json

Fetched from:
- https://opendataapi.dmi.dk/v2/metObs/api
- https://opendataapi.dmi.dk/v2/climateData/api

on 2026-09-21.

Neither document declares a license. DMI Open Data itself is published under
CC BY 4.0 (see https://www.dmi.dk/friedata/dokumentation/terms-of-use), but the
OpenAPI documents fetched here say nothing about licensing themselves; that
statement is provenance, not a license grant from the spec.

Used as regression fixtures for the parameter-rendering seam (see
docs/adr/0002-parameter-rendering-seam.md): both specs declare a `bbox` query
parameter of `style: form`, but only `dmi-metobs.json` sets `explode: false`
explicitly. `dmi-climateData.json` omits `explode`, which the OpenAPI spec
defaults to `true` for `style: form`. The two fixtures are otherwise kept as
they were fetched, not hand-edited, so the difference in emitted query
strings between them is spec-faithful rather than a fixture artifact.

## `application/geo+json` rewritten to `application/json` in dmi-climateData.json

As fetched, every `.../items` endpoint in `dmi-climateData.json` responds
`application/geo+json`, not `application/json`; `OpenApiSpec.hasJsonResponse`
only recognises the exact `application/json` media type, so as fetched these
endpoints would not be exposed as tables at all. In production this never
happens: the consuming pipeline (GATE, this connector's only real caller)
rewrites `application/geo+json` -> `application/json` for every response at
fetch time, in `patch_spec()`
(`gate_pipelines/utils/openapi_specs.py`), specifically so these endpoints
*are* exposed as tables. This vendored fixture has been patched the same way
so it reflects what the connector actually receives in production, rather
than the raw upstream document. `dmi-metobs.json` was fetched already
declaring `application/json` for the endpoint these tests use
(`/v2/metObs/collections/station/items`) and needed no such patch; its
sibling `observation/items` endpoint is still `application/geo+json` and was
deliberately left alone, since no test here depends on it.

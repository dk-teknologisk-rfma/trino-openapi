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
defaults to `true` for `style: form`. The two fixtures are deliberately kept
as they were fetched, not hand-edited, so the difference in emitted query
strings between them is spec-faithful rather than a fixture artifact.

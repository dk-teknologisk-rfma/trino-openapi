---
status: accepted
---

# Unsatisfiable security requirements are logged, and refused only at the moment they'd otherwise be silently skipped

A security requirement names one or more entries in `components.securitySchemes`
that must all be applied together. If any of those names isn't actually
declared, the requirement can never be satisfied - `Authentication` finds this
out by getting a `NullPointerException` while trying to look the scheme up,
and moves on to the next requirement, on the theory that only one requirement
out of several needs to succeed.

That's fine when *some* requirement for a request is satisfiable. It's not
fine when *none* of them are: the loop simply ends, `filterRequest` sees a
non-empty requirement list (so it doesn't fall back to the configured default
authentication either), and the request goes out with no credentials at all.
Silently. That's the defect this ADR fixes: it's not that unsatisfiable
requirements are tolerated - they must be, or a single stale scheme reference
anywhere in someone else's spec would lock a user out of the whole silo - it's
that the *failure to authenticate the request* was invisible.

## Decision

Two changes, at two different times:

1. **At spec-parse time**, `OpenApiSpec` logs a warning for every security
   requirement - root-level or per-operation - that names an undeclared
   scheme. This is diagnostic only; it does not fail catalog creation. Most of
   the time this is a typo or a stale reference in a spec we don't control,
   and the person configuring the catalog should be able to see that without
   waiting for a request to fail.
2. **At request time**, if every requirement that applies to a request turned
   out to be unsatisfiable, `Authentication.filterRequest` now throws a
   `TrinoException` instead of sending the request without credentials. A
   requirement list that resolves to nothing usable is treated the same as a
   request that's missing a required constraint elsewhere in this connector:
   loud, not silent.

## Considered options

**Reject the whole catalog at parse time when any requirement is
unsatisfiable.** Rejected: an OpenAPI spec is frequently not under the
control of whoever is configuring this connector, and a single unsatisfiable
requirement on one obscure operation, in a spec with dozens of them, would
otherwise lock the user out of every table the spec exposes - most of which
may not even use that requirement. Loudness should not cost breadth of silo
support.

**Silently fall back to the configured default authentication when all
requirements are unsatisfiable**, the same way an empty requirement list
does today. Rejected: an operation that explicitly declares security
requirements has opted out of "use whatever default is configured" -
substituting the default anyway when its own requirements all turn out to be
broken is a bigger, more surprising behavior change than simply refusing the
request.

## Consequences

A silo that has always been sending unauthenticated requests to an operation
with unsatisfiable requirements - and has been getting away with it because
the API didn't actually require authentication - will now see that request
fail loudly instead. That is the point: what looked like it was working was
actually just the connector never having asked for credentials at all.

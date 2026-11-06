# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primary: researchers who track the papers their work cites and need to hear when one is retracted, corrected or delisted.

Dev mode only: G4T1 team members, signed in as the `admin` account, demoing the product to markers on a laptop or projector. They walk through poll, change a flag, poll again, and need the demo to read from across a room.

## Product Purpose

Tracks cited papers, raises alerts when a paper's status changes, investigates the change and assesses its impact on the researcher's draft. Dev mode exists so the team can trigger those changes on demand in a demo.

## Operating Context

Dev mode is on only when `DEV_MODE=true`. With it off, the admin account does not exist and its login answers `401`. The admin has no papers and no poll of their own: the researcher starts every poll.

## Capabilities and Constraints

Dev mode can: list researcher accounts, read any paper's snapshots, alerts and reports, change `is_retracted`, `in_doaj`, `cited_by_count`, remove Crossref notices by type, clear a paper's history, and add a fake report in one of three states. It cannot edit anything else (docs/specs/2026-11-06-dev-mode.md).

Edits only surface a change the paper really has: a retraction cannot be raised on a paper that is not retracted.

## Brand Commitments

The dev mode screen is a man with a giant beer belly, and the oval belly is the UI. It started from the user's sketch; they then asked for it to be more realistic and released the sketch's exact outline. The belly bounces on any click outside its buttons. This applies to dev mode only; the researcher app is not bound by it.

## Evidence on Hand

No real customers, testimonials or metrics. Fake reports are made-up content and say so.

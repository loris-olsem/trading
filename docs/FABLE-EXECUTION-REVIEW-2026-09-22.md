# Fable execution review and disposition

The owner explicitly requested this consultation. The original review is
[preserved verbatim](../work/fable-review/runs/20260922T194204Z-9688bb3a/review.md),
with its [request](../work/fable-review/execution-failure-request.md). Fable had
read-only source access; there were no permission denials and no execution tests
against the live account. Revision reviewed: implementation ae3ccaa, state 54dde35.

Fable's verdict: "revise — one justified code change, one evidence-based experiment,
and an honest constraint conflict to surface".

## Accepted fix

The live ARGT rejection 2039 establishes the IOC/CFD incompatibility missed by
the app. ARGT and SMH currently have CFD profiles. Preflight, policy and payload
construction now reject CFD IOC entries/additions with
`CAPPED_ORDER_REQUIRES_REAL_ASSET`. The report explains the price-policy conflict;
the app does not substitute market orders, change instrument identity or force
real settlement without eligibility evidence. CFD closes and protection are not
removed. Existing rejected attempts remain in the recovery journal.

## Corrections to the review

- The 10% limit deviation check already exists in `EtoroClient.prepare`, with a
  tighter 9% planning margin in `Policy.buy`; no duplicate check was needed.
- Seven repeated 1065 rejections support investigating a shared execution route,
  but do not prove the underlying failure is permanent or identify its cause.
- Fable's whole-share hypothesis is not proof of broker compatibility. Generic
  exchange routing does not establish that fractional/amount orders cause 1065.
- Deliberately flooring an intended order to whole shares changes sizing. The
  accepted rule about retaining actual partial fills does not authorize that
  deliberate reduction before submission. We asked the owner explicitly.

## Owner decision

The owner chose: **"Keep the existing sizing; fix only the known defects"**.
No whole-share trial, units conversion, sizing change or uncapped fallback was
implemented. Normal submissions remain v3 limitIOC by amount for eligible real
settlement assets. V3 has not resolved 1065 on the owner's latest run.

## Completion limit

Fixing the CFD defect prevents known-invalid submissions; it does not resolve
the real-stock 1065 execution failure. Neither this review nor fixture tests
establish a working live fill or copy. The app remains unproven end to end under
the existing sizing and cap requirements.

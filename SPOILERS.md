# Legacy Inventory App Spoilers

This document reveals the worst habits baked into the system so refactoring exercises can focus on taming them. Proceed if you want to know exactly how messy things get.

## Global State & Concurrency

- The CLI loop, REST thread, and every helper mutate the same global `List<Map<String,String>>` without synchronization. Random race conditions are part of the charm.
- Global flags like `running`, `lastCommand`, and `lastReport` are reused everywhere, so a REST call can change what the CLI believes just happened.
- Stopping the CLI flips the `running` flag to false, instantly pulling the rug out from under the REST accept loop regardless of active clients.

## Identity Chaos

- Item lookups treat ID, name, category, and location as interchangeable keys. Whichever matches first wins, so deleting by category can remove unexpected records.
- Case-insensitive comparisons collapse distinct IDs like `A12` and `a12`. The last one created usually wins.
- Updates add ad-hoc fields (`updated`, `lastScramble`) that never get cleaned up, bloating records over time.

## Input Handling Oddities

- Numeric parsing silently coerces invalid or blank values to zero, so mistyped quantities crash inventory counts rather than raising errors.
- Negative quantity adjustments snap to zero, but negative prices are allowed until the scramble routine nudges them up.
- Empty strings are acceptable for core properties (name, category, location); they simply show up blank in reports.

## Scramble Routine Shenanigans

- The so-called maintenance task uses divisibility rules: even quantities get halved and inflated; multiples of three gain three units and lose $0.33; everything else is bumped by one unit and $0.05.
- Prices are only clamped when they drop below zero, so repeated scrambles gradually drift values toward unpredictable ranges.
- Every scramble adds a millisecond timestamp string under `lastScramble`, even if the item started without the key.

## Reporting Surprises

- The report caches the generated string globally. Subsequent REST `/report` calls just spit out whatever the CLI last produced, even if inventory changed afterwards.
- Category totals rely on the lossy quantity parser, so malformed data produces misleading numbers with no warning.
- Sorted listings re-copy the mutable maps, but they still reference the same objects, so concurrent edits bleed into the “snapshot.”

## REST Layer Quirks

- The HTTP parser only understands `Content-Length` bodies and treats everything as URL-encoded data, even when you send JSON.
- `POST` and `PUT` `/items` both create new entries; there’s no idempotency, so retrying a request doubles inventory.
- `POST` and `PATCH` `/items/{key}` are aliases; whichever arrives first mutates the shared map with zero validation.
- A DELETE success response returns the entire inventory listing, including the record just removed.
- The listener closes each socket immediately after responding, regardless of headers requesting keep-alive.

## Miscellaneous Gotchas

- All JSON is handcrafted strings without escaping control characters beyond quotes and backslashes. Multiline input will break the format.
- The CLI and REST server auto-start together; failure to bind the port leaves the CLI running while the REST interface silently reports `BOOT_FAIL` through the `REST` command only.
- Every run starts from the same four seed items, so repeat testing resets history whether you want it or not.
- The inspirational motto loader depends on `legacy-support.jar`; if you skip copying it into `lib/`, the app falls back to grumpy warning messages.

Use these spoilers to build targeted tests or to choose which monsters to slay first during refactoring.

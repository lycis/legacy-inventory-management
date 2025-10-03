# Legacy Inventory App

This intentionally fragile inventory management system exists so that teams can practice refactoring a tangled legacy codebase. It mixes a command-line operator console with a hand-built REST listener that both mutate the same shared in-memory inventory state.

## Getting Started

1. Copy the jar-based motto library into place:
   ```bash
   mkdir -p lib
   cp dependencies/legacy-support.jar lib/
   ```
2. Compile and launch with the jar on the classpath:
   ```bash
   javac -cp lib/legacy-support.jar InventoryApp.java
   java -cp .:lib/legacy-support.jar InventoryApp
   ```
   *(Use `.;lib\legacy-support.jar` on Windows.)*
3. The CLI menu appears on standard input while an HTTP server starts on port 8089 in the background.
4. All data is ephemeral—every restart resets the inventory to the baked-in sample records.

## Starting the REST Service

The REST listener boots automatically when the application starts. After launching `java InventoryApp`, the server binds to port 8089 in the background.

- Use the CLI command `REST` at any time to print the current listener status and port.
- If the port is unavailable, the server records a boot failure message that the `REST` command will surface.
- Stopping the CLI with `0`, `EXIT`, `QUIT`, or `Q` also shuts down the REST listener.

## CLI Usage

The CLI never validates inputs cautiously; most fields accept blank or malformed values and silently coerce them to zero. Menu entries:

- `1` Add Item: prompts for name, category, quantity, unit price, and location before appending the record.
- `2` Remove Item: deletes the first match by ID, name, category, or location.
- `3` Change Quantity: adjusts quantity by a signed delta; negative totals are clamped to zero.
- `4` Find Item: performs a substring search across all fields and prints free-form descriptions.
- `5` Show Inventory: dumps every record in its current random order.
- `6` Generate Report: prints a category breakdown, alphabetized listing, and total inventory value.
- `7` Random Maintenance Task: tweaks quantities and prices with arbitrary formulas and stamps a `lastScramble` timestamp.
- `REST` REST Server Status: echoes the listener’s current status and port.
- `0` / `EXIT` / `QUIT` / `Q`: stops both the CLI loop and the REST server.

## REST Interface

The REST layer is a blocking `ServerSocket` loop with naive HTTP parsing. It expects URL-encoded form data either in the query string or request body. No authentication exists; the first caller wins whatever race occurs.

### Base URL

```
http://localhost:8089
```

### Endpoints

- `GET /items` – Returns every item in a handcrafted JSON envelope.
- `GET /items/{id-or-name}` – Finds the first item whose ID, name, category, or location matches exactly.
- `POST /items` – Creates an item. Accepts any of `name|item|title`, `category|cat|group`, `quantity|qty|count`, `price|cost|amount`, and `location|loc|where`.
- `PUT /items` – Same behavior as POST; whichever method you choose appends another item.
- `POST /items/{id-or-name}` – Updates quantity, price, and/or location in-place. Adds an `updated` timestamp field.
- `PATCH /items/{id-or-name}` – Alias for the POST update pathway.
- `DELETE /items/{id-or-name}` – Removes the matching item and returns the shrunken inventory listing.
- `POST /maintenance/scramble` – Runs the random maintenance routine shared with the CLI.
- `GET /report` – Triggers or returns the last generated text report.
- `GET /health` – Reports listener status and current item count.

### HTTP Notes

- Requests with bodies must send `Content-Length`; chunked and keep-alive semantics are ignored.
- Responses always close the socket and include hand-counted content lengths, so mismatches may happen if you edit the payload mid-flight.
- JSON formatting is literal string concatenation; numbers and strings appear as whatever the system currently stores.

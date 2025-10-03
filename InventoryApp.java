// Intentionally awful inventory management system for refactoring practice.
// Everything lives in one file with tangled static state and now even has a hand-rolled REST server.

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InventoryApp {
    // Global mutable state because encapsulation is for future refactorings.
    public static List<Map<String, String>> items = new ArrayList<>();
    public static BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
    public static boolean running = true;
    public static int lastKnownId = 1000;
    public static String lastCommand = "";
    public static String lastReport = null;
    public static int restPort = 8089;
    public static Thread restThread = null;
    public static ServerSocket restServer = null;
    public static String restServerStatus = "NOT_STARTED";
    public static List<String> mottos = new ArrayList<>();

    public static void main(String[] args) {
        // Pre-populate with some nonsense data to make it feel alive.
        makeItem("Paper Clips", "OFFICE", 500, 0.01, "Drawer A");
        makeItem("Stapler", "OFFICE", 12, 4.95, "Drawer B");
        makeItem("Coffee Beans", "BREAKROOM", 7, 11.5, "Pantry");
        makeItem("HDMI Cable", "TECH", 3, 7.99, "Closet");

        loadMottosFromLibrary();
        splash();
        startRestServer();
        while (running) {
            printMenu();
            try {
                String input = console.readLine();
                if (input == null) {
                    running = false;
                    break;
                }
                lastCommand = input.trim().toUpperCase();
                if (lastCommand.equals("1")) {
                    addItemFlow();
                } else if (lastCommand.equals("2")) {
                    removeItemFlow();
                } else if (lastCommand.equals("3")) {
                    changeQuantityFlow();
                } else if (lastCommand.equals("4")) {
                    findItemFlow();
                } else if (lastCommand.equals("5")) {
                    dumpInventory();
                } else if (lastCommand.equals("6")) {
                    printReport();
                } else if (lastCommand.equals("7")) {
                    scrambleItemsForNoReason();
                } else if (lastCommand.equals("REST")) {
                    System.out.println("REST server status: " + restServerStatus + " on port " + restPort);
                } else if (lastCommand.equals("EXIT") || lastCommand.equals("QUIT") || lastCommand.equals("Q") || lastCommand.equals("0")) {
                    running = false;
                } else if (lastCommand.length() == 0) {
                    // Do nothing, keep loop going.
                } else {
                    System.out.println("?? Unknown command. Maybe try the menu options.");
                }
            } catch (IOException e) {
                // Just print it and keep going like everything is fine.
                System.out.println("Error reading command: " + e.getMessage());
            }
        }
        stopRestServer();
        System.out.println("Shutting down the legacy inventory app... bye");
    }

    private static void splash() {
        System.out.println("\n*** WELCOME TO LEGACY INVENTORY 1.1 ***\n");
        System.out.println("This system is old, cranky, and loved by nobody. Now it even speaks HTTP poorly. Enjoy!");
        System.out.println("Inspiration of the day: " + pickMotto());
    }

    private static void printMenu() {
        System.out.println("\n=== MENU ===");
        System.out.println("1. Add Item");
        System.out.println("2. Remove Item");
        System.out.println("3. Change Quantity");
        System.out.println("4. Find Item");
        System.out.println("5. Show Inventory");
        System.out.println("6. Generate Report");
        System.out.println("7. Random Maintenance Task");
        System.out.println("REST. REST Server Status");
        System.out.println("0. Exit");
        System.out.print("Choose: ");
    }

    private static void addItemFlow() {
        try {
            System.out.print("Name: ");
            String name = console.readLine();
            if (name == null) return;
            System.out.print("Category: ");
            String cat = console.readLine();
            if (cat == null) return;
            System.out.print("Quantity: ");
            String qty = console.readLine();
            System.out.print("Unit Price: ");
            String price = console.readLine();
            System.out.print("Location: ");
            String location = console.readLine();
            makeItem(name, cat, parseIntMessily(qty), parseDoubleMessily(price), location);
            System.out.println("Item added (probably). Total items: " + items.size());
        } catch (IOException e) {
            System.out.println("Something went wrong but let's ignore it: " + e.getMessage());
        }
    }

    private static void removeItemFlow() {
        try {
            System.out.print("Enter ID or Name to remove: ");
            String what = console.readLine();
            if (what == null || what.trim().length() == 0) {
                System.out.println("Need an identifier to remove.");
                return;
            }
            Map<String, String> item = findItemByAny(what);
            if (item != null) {
                items.remove(item);
                System.out.println("Removed: " + describeItem(item));
            } else {
                System.out.println("Could not find item: " + what);
            }
        } catch (IOException e) {
            System.out.println("Removal failed because of reasons: " + e.getMessage());
        }
    }

    private static void changeQuantityFlow() {
        try {
            System.out.print("Enter ID or Name to change quantity: ");
            String txt = console.readLine();
            Map<String, String> item = findItemByAny(txt);
            if (item == null) {
                System.out.println("No such item. Sorry? Not sorry?");
                return;
            }
            System.out.print("Change amount (+/-): ");
            String delta = console.readLine();
            int current = parseIntMessily(item.get("quantity"));
            int change = parseIntMessily(delta);
            int newValue = current + change;
            if (newValue < 0) {
                newValue = 0; // Because we don't do negatives.
            }
            item.put("quantity", String.valueOf(newValue));
            System.out.println("Quantity updated. New quantity: " + newValue);
        } catch (IOException e) {
            System.out.println("Quantity change aborted: " + e.getMessage());
        }
    }

    private static void findItemFlow() {
        try {
            System.out.print("Search term (name/category/location/id): ");
            String term = console.readLine();
            if (term == null) return;
            List<Map<String, String>> found = findItems(term);
            if (found.isEmpty()) {
                System.out.println("No luck finding \"" + term + "\". Try again.");
            } else {
                System.out.println("Found " + found.size() + " item(s):");
                for (Map<String, String> map : found) {
                    System.out.println(" - " + describeItem(map));
                }
            }
        } catch (IOException e) {
            System.out.println("Search exploded: " + e.getMessage());
        }
    }

    private static void dumpInventory() {
        if (items.isEmpty()) {
            System.out.println("Inventory is empty. Maybe go shopping.");
            return;
        }
        System.out.println("\nALL ITEMS (unordered, because chaos):");
        for (Map<String, String> it : items) {
            System.out.println(describeItem(it));
        }
    }

    private static void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nINVENTORY REPORT\n");
        sb.append("Last command: ").append(lastCommand).append("\n\n");
        sb.append("Counts by category:\n");
        Map<String, Integer> totals = new HashMap<>();
        for (Map<String, String> item : items) {
            String cat = item.get("category");
            Integer value = totals.get(cat);
            if (value == null) value = 0;
            totals.put(cat, value + parseIntMessily(item.get("quantity")));
        }
        for (String key : totals.keySet()) {
            sb.append("  ").append(key).append(": ").append(totals.get(key)).append("\n");
        }
        sb.append("\nSorted by name (because why not now?)\n");
        List<Map<String, String>> snapshot = new ArrayList<>();
        snapshot.addAll(items);
        Collections.sort(snapshot, new Comparator<Map<String, String>>() {
            @Override
            public int compare(Map<String, String> a, Map<String, String> b) {
                return a.get("name").compareToIgnoreCase(b.get("name"));
            }
        });
        for (Map<String, String> sorted : snapshot) {
            sb.append(" * ").append(describeItem(sorted)).append("\n");
        }
        sb.append("\nTotal inventory value: $").append(calculateTotalValue());
        sb.append("\nSlogan: ").append(pickMotto());
        System.out.println(sb.toString());
        lastReport = sb.toString();
    }

    private static void scrambleItemsForNoReason() {
        // Perform arbitrary adjustments to simulate weird maintenance.
        if (items.isEmpty()) {
            System.out.println("Nothing to scramble. Add things first.");
            return;
        }
        for (Map<String, String> item : items) {
            int qty = parseIntMessily(item.get("quantity"));
            double price = parseDoubleMessily(item.get("price"));
            if (qty % 2 == 0) {
                qty = qty / 2 + 1;
                price = price * 1.07;
            } else if (qty % 3 == 0) {
                qty = qty + 3;
                price = price - 0.33;
            } else {
                qty = qty + 1;
                price = price + 0.05;
            }
            if (price < 0) {
                price = 0.01;
            }
            item.put("quantity", String.valueOf(qty));
            item.put("price", String.valueOf(price));
            item.put("lastScramble", String.valueOf(System.currentTimeMillis()));
        }
        System.out.println("Maintenance task complete. Inventory may now be worse.");
    }

    // ==== Core business logic tangles (still global, still brittle) ====

    private static void makeItem(String name, String category, int quantity, double price, String location) {
        Map<String, String> item = new HashMap<>();
        item.put("id", String.valueOf(++lastKnownId));
        item.put("name", name);
        item.put("category", category);
        item.put("quantity", String.valueOf(quantity));
        item.put("price", String.valueOf(price));
        item.put("location", location);
        item.put("created", String.valueOf(System.currentTimeMillis()));
        // Some entirely pointless fields for the future refactoring heroes.
        item.put("notes", "");
        item.put("flag", "NONE");
        items.add(item);
    }

    private static Map<String, String> findItemByAny(String anything) {
        if (anything == null) return null;
        String trimmed = anything.trim();
        for (Map<String, String> map : items) {
            if (trimmed.equalsIgnoreCase(map.get("id"))) {
                return map;
            }
            if (map.get("name") != null && map.get("name").equalsIgnoreCase(trimmed)) {
                return map;
            }
            if (map.get("category") != null && map.get("category").equalsIgnoreCase(trimmed)) {
                return map;
            }
            if (map.get("location") != null && map.get("location").equalsIgnoreCase(trimmed)) {
                return map;
            }
        }
        return null;
    }

    private static List<Map<String, String>> findItems(String term) {
        List<Map<String, String>> found = new ArrayList<>();
        if (term == null) {
            return found;
        }
        String normalized = term.trim().toLowerCase();
        for (Map<String, String> map : items) {
            if (mapMatches(map, normalized)) {
                found.add(map);
            }
        }
        return found;
    }

    private static boolean mapMatches(Map<String, String> map, String term) {
        if (map == null) {
            return false;
        }
        for (String value : map.values()) {
            if (value != null && value.toLowerCase().contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String describeItem(Map<String, String> item) {
        // Hard-coded string building because we don't believe in templates.
        StringBuilder sb = new StringBuilder();
        sb.append("[#").append(item.get("id"));
        sb.append("] ");
        sb.append(item.get("name"));
        sb.append(" | cat=").append(item.get("category"));
        sb.append(" | qty=").append(item.get("quantity"));
        sb.append(" | price=").append(item.get("price"));
        sb.append(" | where=").append(item.get("location"));
        if (item.containsKey("flag") && item.get("flag") != null && !item.get("flag").isEmpty()) {
            sb.append(" | flag=").append(item.get("flag"));
        }
        return sb.toString();
    }

    private static int parseIntMessily(String value) {
        if (value == null || value.trim().length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // Just swallow it and pretend it's zero.
            return 0;
        }
    }

    private static double parseDoubleMessily(String value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double calculateTotalValue() {
        double total = 0.0;
        for (int i = 0; i < items.size(); i++) {
            Map<String, String> map = items.get(i);
            int qty = parseIntMessily(map.get("quantity"));
            double price = parseDoubleMessily(map.get("price"));
            total = total + (qty * price);
        }
        // Format? Nah. Good enough.
        return total;
    }

    // ==== Low-level REST interface (the part future engineers will run from) ====

    private static void startRestServer() {
        if (restThread != null) {
            return; // Already running or at least we hope so.
        }
        restThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    restServer = new ServerSocket(restPort);
                    restServerStatus = "LISTENING";
                    while (running) {
                        Socket socket = null;
                        try {
                            socket = restServer.accept();
                            handleRestClient(socket);
                        } catch (IOException acceptProblem) {
                            restServerStatus = "ACCEPT_FAIL:" + acceptProblem.getMessage();
                            if (!running) {
                                break;
                            }
                        } finally {
                            if (socket != null && !socket.isClosed()) {
                                try {
                                    socket.close();
                                } catch (IOException ignore) {
                                    // Already awful.
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    restServerStatus = "BOOT_FAIL:" + e.getMessage();
                } finally {
                    closeRestSocketQuietly();
                }
            }
        }, "LegacyRestThread");
        restThread.setDaemon(true);
        restThread.start();
    }

    private static void handleRestClient(Socket socket) {
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().length() == 0) {
                sendResponse(writer, 400, "Bad Request", "text/plain", "Empty request");
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            int contentLength = 0;
            while ((headerLine = reader.readLine()) != null && headerLine.length() > 0) {
                int idx = headerLine.indexOf(":");
                if (idx > 0) {
                    String key = headerLine.substring(0, idx).trim().toLowerCase();
                    String value = headerLine.substring(idx + 1).trim();
                    headers.put(key, value);
                    if (key.equals("content-length")) {
                        contentLength = parseIntMessily(value);
                    }
                }
            }
            String body = "";
            if (contentLength > 0) {
                char[] data = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = reader.read(data, read, contentLength - read);
                    if (r == -1) {
                        break;
                    }
                    read += r;
                }
                body = new String(data, 0, read);
            }
            processRestCall(writer, method, path, body);
        } catch (IOException e) {
            try {
                sendResponse(writer, 500, "Server Error", "text/plain", "Something broke: " + e.getMessage());
            } catch (IOException ignored) {
                // Can't even send errors properly.
            }
        }
    }

    private static void processRestCall(BufferedWriter writer, String method, String path, String body) throws IOException {
        String cleanPath = path;
        String queryStuff = "";
        int qPos = path.indexOf("?");
        if (qPos >= 0) {
            cleanPath = path.substring(0, qPos);
            queryStuff = path.substring(qPos + 1);
        }
        Map<String, String> params = new HashMap<>();
        params.putAll(parseKeyValuePairs(queryStuff));
        if (body != null && body.trim().length() > 0) {
            params.putAll(parseKeyValuePairs(body));
        }
        if (method.equals("GET") && cleanPath.equals("/items")) {
            sendResponse(writer, 200, "OK", "application/json", buildItemsJson(items));
        } else if (method.equals("GET") && cleanPath.startsWith("/items/")) {
            Map<String, String> found = findItemByAny(cleanPath.substring(7));
            if (found == null) {
                sendResponse(writer, 404, "Not Found", "application/json", "{\"error\":\"Missing item\"}");
            } else {
                sendResponse(writer, 200, "OK", "application/json", buildItemJson(found));
            }
        } else if ((method.equals("POST") || method.equals("PUT")) && cleanPath.equals("/items")) {
            String name = pickFirst(params, "name", "item", "title");
            if (name == null || name.trim().length() == 0) {
                sendResponse(writer, 422, "Unprocessable", "application/json", "{\"error\":\"Need name\"}");
                return;
            }
            String category = pickFirst(params, "category", "cat", "group");
            int quantity = parseIntMessily(pickFirst(params, "quantity", "qty", "count"));
            double price = parseDoubleMessily(pickFirst(params, "price", "cost", "amount"));
            String location = pickFirst(params, "location", "loc", "where");
            if (location == null) {
                location = "UNKNOWN";
            }
            makeItem(name, category == null ? "MISC" : category, quantity, price, location);
            sendResponse(writer, 201, "Created", "application/json", buildItemsJson(items));
        } else if ((method.equals("POST") || method.equals("PATCH")) && cleanPath.startsWith("/items/")) {
            Map<String, String> existing = findItemByAny(cleanPath.substring(7));
            if (existing == null) {
                sendResponse(writer, 404, "Not Found", "application/json", "{\"error\":\"Nothing to update\"}");
                return;
            }
            if (params.containsKey("quantity")) {
                existing.put("quantity", String.valueOf(parseIntMessily(params.get("quantity"))));
            }
            if (params.containsKey("price")) {
                existing.put("price", String.valueOf(parseDoubleMessily(params.get("price"))));
            }
            if (params.containsKey("location")) {
                existing.put("location", params.get("location"));
            }
            existing.put("updated", String.valueOf(System.currentTimeMillis()));
            sendResponse(writer, 200, "OK", "application/json", buildItemJson(existing));
        } else if (method.equals("DELETE") && cleanPath.startsWith("/items/")) {
            Map<String, String> toRemove = findItemByAny(cleanPath.substring(7));
            if (toRemove == null) {
                sendResponse(writer, 404, "Not Found", "application/json", "{\"error\":\"Cannot delete\"}");
            } else {
                items.remove(toRemove);
                sendResponse(writer, 200, "OK", "application/json", buildItemsJson(items));
            }
        } else if (method.equals("POST") && cleanPath.equals("/maintenance/scramble")) {
            scrambleItemsForNoReason();
            sendResponse(writer, 200, "OK", "application/json", buildItemsJson(items));
        } else if (method.equals("GET") && cleanPath.equals("/report")) {
            if (lastReport == null) {
                printReport();
            }
            sendResponse(writer, 200, "OK", "text/plain", lastReport == null ? "Report unavailable" : lastReport);
        } else if (method.equals("GET") && cleanPath.equals("/health")) {
            sendResponse(writer, 200, "OK", "application/json", "{\"status\":\"" + restServerStatus + "\",\"items\":" + items.size() + "}");
        } else {
            sendResponse(writer, 404, "Not Found", "application/json", "{\"error\":\"Legacy endpoint not found\"}");
        }
    }

    private static Map<String, String> parseKeyValuePairs(String data) {
        Map<String, String> map = new HashMap<>();
        if (data == null || data.trim().length() == 0) {
            return map;
        }
        String[] pieces = data.split("&");
        for (String piece : pieces) {
            if (piece.trim().length() == 0) continue;
            String[] kv = piece.split("=", 2);
            String key = decodeMaybe(kv[0]);
            String value = kv.length > 1 ? decodeMaybe(kv[1]) : "";
            map.put(key, value);
        }
        return map;
    }

    private static String decodeMaybe(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }

    private static String buildItemsJson(List<Map<String, String>> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"items\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(buildItemJson(list.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String buildItemJson(Map<String, String> item) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(asJsonValue(item.get("id")));
        sb.append(",\"name\":").append(asJsonValue(item.get("name")));
        sb.append(",\"category\":").append(asJsonValue(item.get("category")));
        sb.append(",\"quantity\":").append(asJsonValue(item.get("quantity")));
        sb.append(",\"price\":").append(asJsonValue(item.get("price")));
        sb.append(",\"location\":").append(asJsonValue(item.get("location")));
        if (item.containsKey("flag")) {
            sb.append(",\"flag\":").append(asJsonValue(item.get("flag")));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String asJsonValue(String text) {
        if (text == null) {
            return "null";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String pickFirst(Map<String, String> data, String... keys) {
        for (String k : keys) {
            if (data.containsKey(k) && data.get(k) != null && data.get(k).trim().length() > 0) {
                return data.get(k);
            }
        }
        return null;
    }

    private static void sendResponse(BufferedWriter writer, int status, String message, String contentType, String body) throws IOException {
        String payload = body == null ? "" : body;
        writer.write("HTTP/1.1 " + status + " " + message + "\r\n");
        writer.write("Content-Type: " + contentType + "\r\n");
        writer.write("Content-Length: " + payload.getBytes().length + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(payload);
        writer.flush();
    }

    private static void stopRestServer() {
        running = false; // Just in case someone called this directly.
        closeRestSocketQuietly();
        restServerStatus = "STOPPED";
    }

    private static void closeRestSocketQuietly() {
        if (restServer != null && !restServer.isClosed()) {
            try {
                restServer.close();
            } catch (IOException ignored) {
                // Can't even close properly.
            }
        }
        restServer = null;
    }

    private static void loadMottosFromLibrary() {
        if (!mottos.isEmpty()) {
            return;
        }
        InputStream stream = InventoryApp.class.getClassLoader().getResourceAsStream("legacy/support/mottos.txt");
        if (stream == null) {
            mottos.add("(Missing legacy-support.jar – copy it into lib/ before compiling.)");
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() > 0) {
                    mottos.add(trimmed);
                }
            }
        } catch (IOException e) {
            mottos.add("(Error loading mottos: " + e.getMessage() + ")");
        }
        if (mottos.isEmpty()) {
            mottos.add("(Library motto list was empty.)");
        }
    }

    private static String pickMotto() {
        if (mottos.isEmpty()) {
            return "(no motto)";
        }
        int index = (int) (System.currentTimeMillis() % mottos.size());
        return mottos.get(index);
    }
}

// StoreBatchSync represents the "modern" rewrite of the nightly job.
// It still has plenty of rough edges, but the structure is cleaner than the rest of the system.

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class StoreBatchSync {
    public static void main(String[] args) {
        SyncConfig config = SyncConfig.fromArgs(args);
        SyncLogger logger = new SyncLogger();

        logger.info("StoreBatchSync starting for store=" + config.storeName);
        if (config.inventoryFile == null) {
            logger.error("No inventory file specified (use file=/path/to/file.csv).");
            logger.flush();
            return;
        }

        InventoryFileLoader loader = new InventoryFileLoader(config.storeName, logger);
        RestInventoryClient client = new RestInventoryClient(config.baseUrl, config.dryRun, logger);
        SyncEngine engine = new SyncEngine(config, loader, client, logger);
        HourlyRunner runner = new HourlyRunner(engine, logger);

        runner.runForever();
    }

    // Holds user-supplied settings with naive parsing.
    static class SyncConfig {
        final String storeName;
        final String inventoryFile;
        final String baseUrl;
        final boolean dryRun;

        SyncConfig(String storeName, String inventoryFile, String baseUrl, boolean dryRun) {
            this.storeName = storeName == null ? "UNKNOWN_STORE" : storeName;
            this.inventoryFile = inventoryFile;
            this.baseUrl = baseUrl == null ? "http://localhost:8089" : baseUrl;
            this.dryRun = dryRun;
        }

        static SyncConfig fromArgs(String[] args) {
            String store = null;
            String file = null;
            String url = null;
            boolean dry = false;
            if (args != null) {
                for (String arg : args) {
                    if (arg == null) {
                        continue;
                    }
                    if (arg.startsWith("store=")) {
                        store = arg.substring(6);
                    } else if (arg.startsWith("file=")) {
                        file = arg.substring(5);
                    } else if (arg.startsWith("url=")) {
                        url = arg.substring(4);
                    } else if (arg.equalsIgnoreCase("dry")) {
                        dry = true;
                    }
                }
            }
            return new SyncConfig(store, file, url, dry);
        }
    }

    // Crude logger that buffers and prints on flush.
    static class SyncLogger {
        private final List<String> lines = new ArrayList<>();

        void info(String message) {
            lines.add("INFO  " + message);
        }

        void warn(String message) {
            lines.add("WARN  " + message);
        }

        void error(String message) {
            lines.add("ERROR " + message);
        }

        void flush() {
            for (String line : lines) {
                System.out.println(line);
            }
            lines.clear();
        }
    }

    // Handles the "run every hour" loop logic.
    static class HourlyRunner {
        private final SyncEngine engine;
        private final SyncLogger logger;

        HourlyRunner(SyncEngine engine, SyncLogger logger) {
            this.engine = engine;
            this.logger = logger;
        }

        void runForever() {
            while (true) {
                waitForTopOfHour();
                engine.executeCycle();
                logger.flush();
            }
        }

        private void waitForTopOfHour() {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
            Duration wait = Duration.between(now, nextHour);
            long millis = Math.max(wait.toMillis(), 1000);
            logger.info("Sleeping until top of hour: " + millis + " ms");
            logger.flush();
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Orchestrates a single sync cycle.
    static class SyncEngine {
        private final SyncConfig config;
        private final InventoryFileLoader loader;
        private final RestInventoryClient client;
        private final SyncLogger logger;

        SyncEngine(SyncConfig config, InventoryFileLoader loader, RestInventoryClient client, SyncLogger logger) {
            this.config = config;
            this.loader = loader;
            this.client = client;
            this.logger = logger;
        }

        void executeCycle() {
            logger.info("=== Sync cycle start @ " + Instant.now() + " ===");
            List<InventoryRecord> records = loader.load(config.inventoryFile);
            if (records.isEmpty()) {
                logger.warn("Inventory file produced zero records.");
                return;
            }
            int created = 0;
            int updated = 0;
            for (InventoryRecord record : records) {
                if (!record.isValid()) {
                    logger.warn("Skipping invalid record: " + record.rawLine);
                    continue;
                }
                String remoteId = client.findRemoteIdentifier(record.name);
                if (remoteId == null) {
                    if (client.createItem(record)) {
                        created++;
                    }
                } else {
                    if (client.updateItem(remoteId, record)) {
                        updated++;
                    }
                }
            }
            logger.info("Cycle summary: created=" + created + " updated=" + updated);
        }
    }

    // Represents a parsed inventory row.
    static class InventoryRecord {
        final String rawLine;
        final String name;
        final String category;
        final String quantity;
        final String price;
        final String location;

        InventoryRecord(String rawLine, String name, String category, String quantity, String price, String location) {
            this.rawLine = rawLine;
            this.name = name;
            this.category = category;
            this.quantity = quantity;
            this.price = price;
            this.location = location;
        }

        boolean isValid() {
            return name != null && name.length() > 0 && quantity != null && quantity.length() > 0;
        }
    }

    // Loads CSV-ish data into inventory records.
    static class InventoryFileLoader {
        private final String storeName;
        private final SyncLogger logger;

        InventoryFileLoader(String storeName, SyncLogger logger) {
            this.storeName = storeName;
            this.logger = logger;
        }

        List<InventoryRecord> load(String filePath) {
            if (filePath == null) {
                return Collections.emptyList();
            }
            File file = new File(filePath);
            if (!file.exists()) {
                logger.error("Inventory file not found: " + filePath);
                return Collections.emptyList();
            }
            List<InventoryRecord> records = new ArrayList<>();
            InputStream in = null;
            BufferedReader reader = null;
            try {
                in = new FileInputStream(file);
                reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    InventoryRecord record = parseLine(trimmed);
                    records.add(record);
                }
            } catch (IOException e) {
                logger.error("Failed reading inventory: " + e.getMessage());
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            logger.info("Loaded " + records.size() + " record(s) from file.");
            return records;
        }

        private InventoryRecord parseLine(String line) {
            String[] parts = line.split(",");
            String name = grab(parts, 0);
            String category = grab(parts, 1);
            String quantity = grab(parts, 2);
            String price = grab(parts, 3);
            String location = grab(parts, 4);
            if (location == null || location.isEmpty()) {
                location = storeName + " (auto)";
            }
            return new InventoryRecord(line, name, category, quantity, price, location);
        }

        private String grab(String[] parts, int index) {
            if (index >= parts.length) {
                return null;
            }
            return parts[index].trim();
        }
    }

    // Talks to the old REST endpoint with simple HTTP calls.
    static class RestInventoryClient {
        private final String baseUrl;
        private final boolean dryRun;
        private final SyncLogger logger;

        RestInventoryClient(String baseUrl, boolean dryRun, SyncLogger logger) {
            this.baseUrl = baseUrl;
            this.dryRun = dryRun;
            this.logger = logger;
        }

        String findRemoteIdentifier(String itemName) {
            if (itemName == null || itemName.isEmpty()) {
                return null;
            }
            String encoded = urlEncode(itemName);
            String url = baseUrl + "/items/" + encoded;
            String payload = request(url, "GET", null);
            if (payload == null || payload.contains("\"error\"")) {
                logger.info("Lookup miss for " + itemName);
                return null;
            }
            String id = extractField(payload, "id");
            if (id != null && !id.isEmpty()) {
                logger.info("Lookup hit for " + itemName + " => id=" + id);
                return id;
            }
            logger.warn("Ambiguous lookup for " + itemName + "; falling back to name");
            return itemName;
        }

        boolean createItem(InventoryRecord record) {
            if (dryRun) {
                logger.info("DRY RUN: would create " + record.name);
                return false;
            }
            String body = toForm(record, true);
            String response = request(baseUrl + "/items", "POST", body);
            boolean success = response != null && response.contains("\"items\"");
            if (success) {
                logger.info("Created remote item " + record.name);
            } else {
                logger.warn("Create failed for " + record.name + " => " + response);
            }
            return success;
        }

        boolean updateItem(String idOrName, InventoryRecord record) {
            if (dryRun) {
                logger.info("DRY RUN: would update " + idOrName + " => qty=" + record.quantity);
                return false;
            }
            String body = toForm(record, false);
            String encoded = urlEncode(idOrName);
            String response = request(baseUrl + "/items/" + encoded, "POST", body);
            boolean success = response != null && response.contains("\"quantity\"");
            if (success) {
                logger.info("Updated remote item " + idOrName + " => qty=" + record.quantity);
            } else {
                logger.warn("Update failed for " + idOrName + " => " + response);
            }
            return success;
        }

        private String toForm(InventoryRecord record, boolean includeName) {
            Map<String, String> values = new HashMap<>();
            if (includeName) {
                values.put("name", record.name);
                values.put("category", record.category);
            }
            values.put("quantity", record.quantity);
            values.put("price", record.price);
            values.put("location", record.location);
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                pairs.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
            }
            return String.join("&", pairs);
        }

        private String request(String urlString, String method, String body) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(4000);
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
                        writer.write(body);
                    }
                }
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) {
                    return null;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            } catch (IOException e) {
                logger.error("HTTP error " + method + " " + urlString + " => " + e.getMessage());
                return null;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private String extractField(String json, String key) {
            if (json == null || key == null) {
                return null;
            }
            String needle = "\"" + key + "\":";
            int idx = json.indexOf(needle);
            if (idx < 0) {
                return null;
            }
            int start = idx + needle.length();
            if (start >= json.length()) {
                return null;
            }
            if (json.charAt(start) == '\"') {
                start++;
                int end = json.indexOf('"', start);
                if (end > start) {
                    return json.substring(start, end);
                }
            } else {
                int end = start;
                while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                    end++;
                }
                return json.substring(start, end).trim();
            }
            return null;
        }

        private String urlEncode(String value) {
            if (value == null) {
                return "";
            }
            try {
                return URLEncoder.encode(value, "UTF-8");
            } catch (Exception e) {
                return value;
            }
        }
    }
}

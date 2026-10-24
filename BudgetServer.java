
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BudgetServer {

    private static final List<Transaction> transactions =
            new ArrayList<>();

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    static class Transaction {
        String description;
        double amount;
        String type;
        String date;

        Transaction(String description, double amount, String type) {
            this.description = description;
            this.amount = amount;
            this.type = type;
            this.date = LocalDateTime.now().format(DATE_FORMAT);
        }
    }

    public static void main(String[] args) throws IOException {

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 8080), 0);

        server.createContext("/api/transactions",
                BudgetServer::handleTransactions);

        server.createContext("/", BudgetServer::handleFiles);

        server.start();

        System.out.println("Budget App is running!");
        System.out.println("Open http://127.0.0.1:8080");
    }

    private static void handleTransactions(HttpExchange exchange)
            throws IOException {

        String method = exchange.getRequestMethod();

        if (method.equals("GET")) {
            sendJson(exchange, 200, getSummary());
            return;
        }

        if (method.equals("POST")) {
            try {
                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);

                Map<String, String> form = parseForm(body);

                String description = form.getOrDefault(
                        "description", "").trim();

                double amount = Double.parseDouble(
                        form.getOrDefault("amount", ""));

                String type = form.getOrDefault("type", "");

                if (description.isEmpty()
                        || description.length() > 100
                        || !Double.isFinite(amount)
                        || amount <= 0
                        || amount > 1_000_000_000_000.0
                        || !(type.equals("income")
                        || type.equals("expense"))) {

                    sendJson(exchange, 400,
                            "{\"error\":\"Invalid transaction details.\"}");
                    return;
                }

                synchronized (transactions) {
                    transactions.add(
                            new Transaction(description, amount, type));
                }

                sendJson(exchange, 201, "{\"success\":true}");

            } catch (NumberFormatException e) {
                sendJson(exchange, 400,
                        "{\"error\":\"Enter a valid amount.\"}");
            }

            return;
        }

        exchange.getResponseHeaders().set("Allow", "GET, POST");
        sendJson(exchange, 405,
                "{\"error\":\"Method not allowed.\"}");
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> result = new LinkedHashMap<>();

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);

            String key = URLDecoder.decode(
                    parts[0], StandardCharsets.UTF_8);

            String value = parts.length > 1
                    ? URLDecoder.decode(
                            parts[1], StandardCharsets.UTF_8)
                    : "";

            result.put(key, value);
        }

        return result;
    }

    private static String getSummary() {
        double income = 0;
        double expenses = 0;
        StringBuilder items = new StringBuilder();

        synchronized (transactions) {
            for (int i = 0; i < transactions.size(); i++) {
                Transaction t = transactions.get(i);

                if (t.type.equals("income")) {
                    income += t.amount;
                } else {
                    expenses += t.amount;
                }

                if (i > 0) {
                    items.append(",");
                }

                items.append("{")
                    .append("\"description\":\"")
                    .append(escapeJson(t.description))
                    .append("\",")
                    .append("\"amount\":").append(t.amount).append(",")
                    .append("\"type\":\"").append(t.type).append("\",")
                    .append("\"date\":\"")
                    .append(escapeJson(t.date)).append("\"")
                    .append("}");
            }
        }

        return "{"
                + "\"income\":" + income + ","
                + "\"expenses\":" + expenses + ","
                + "\"balance\":" + (income - expenses) + ","
                + "\"transactions\":[" + items + "]"
                + "}";
    }

    private static String escapeJson(String text) {
        StringBuilder result = new StringBuilder();

        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x",
                                (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }

        return result.toString();
    }

    private static void sendJson(
            HttpExchange exchange, int status, String response)
            throws IOException {

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        exchange.sendResponseHeaders(status, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void handleFiles(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendText(exchange, 405, "Method not allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();

        if (path.equals("/")) {
            path = "/index.html";
        }

        String fileName;

        if (path.equals("/index.html")) {
            fileName = "index.html";
        } else if (path.equals("/style.css")) {
            fileName = "style.css";
        } else {
            sendText(exchange, 404, "File not found");
            return;
        }

        Path file = Path.of(fileName);

        if (!Files.isRegularFile(file)) {
            sendText(exchange, 404,
                    "File not found. Run the server from your project folder.");
            return;
        }

        String contentType = fileName.endsWith(".css")
                ? "text/css; charset=UTF-8"
                : "text/html; charset=UTF-8";

        byte[] bytes = Files.readAllBytes(file);

        exchange.getResponseHeaders().set(
                "Content-Type", contentType);
        exchange.getResponseHeaders().set(
                "X-Content-Type-Options", "nosniff");

        exchange.sendResponseHeaders(200, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendText(
            HttpExchange exchange, int status, String message)
            throws IOException {

        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type", "text/plain; charset=UTF-8");

        exchange.sendResponseHeaders(status, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

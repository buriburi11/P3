import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** P3 starter. Java 21: java --source 21 cpe.java [port] (default 8080).
 * Complete RoutingController.handle and ForwardingTableBuilder.build.
 * Find the P3 Prompt and Tutorial under Modules in Canvas.
 * All other runtime classes are provided infrastructure.
 */
public class cpe {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: java cpe.java [port]");
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        RoutingController controller = new RoutingController();
        Semaphore connections = new Semaphore(16);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", port));
            System.err.println("READY port=" + server.getLocalPort());
            while (true) {
                Socket socket = server.accept();
                if (!connections.tryAcquire()) { socket.close(); continue; }
                Thread.ofVirtual().start(() -> {
                    try (socket) {
                        socket.setSoTimeout(30_000);
                        serve(socket, controller);
                    } catch (IOException e) {
                        System.err.println("CONNECTION " + e.getMessage());
                    } finally { connections.release(); }
                });
            }
        }
    }

    static void serve(Socket socket, RoutingController controller) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = readLine(in)) != null) {
            String id = null;
            Map<String, Object> response;
            try {
                Map<String, Object> request = Json.object(Json.parse(line));
                id = Fields.text(request, "id");
                String type = Fields.text(request, "type");
                switch (type) {
                    case "INIT", "LINK_DOWN", "LINK_UP" -> {
                        TopologyEvent event = TopologyEvent.decode(id, type, request);
                        AuditRecord audit = controller.handle(event);
                        response = audit.wire();
                        System.err.println(Json.write(audit.wire()));
                    }
                    case "SNAPSHOT" -> {
                        ForwardingSnapshot snapshot = controller.current();
                        response = Fields.map("type", "SNAPSHOT", "id", id,
                                "generation", snapshot.generation(), "tables", snapshot.wireTables());
                    }
                    case "QUERY" -> {
                        List<Object> queries = Fields.array(request, "queries");
                        ForwardingSnapshot snapshot = controller.current();
                        List<Object> routes = new ArrayList<>();
                        for (Object value : queries) {
                            Map<String, Object> query = Json.object(value);
                            String from = Fields.text(query, "from"), to = Fields.text(query, "to");
                            RouteEntry route = snapshot.route(from, to);
                            Map<String, Object> entry = route.wire();
                            entry.put("from", from); entry.put("to", to); routes.add(entry);
                        }
                        response = Fields.map("type", "ROUTES", "id", id,
                                "generation", snapshot.generation(), "routes", routes);
                    }
                    default -> throw new IllegalArgumentException("Unknown message type: " + type);
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                response = Fields.map("type", "ERROR", "id", id, "message", e.getMessage());
            }
            out.write(Json.write(response)); out.newLine(); out.flush();
        }
    }

    // Bound each wire message instead of allocating an unlimited readLine buffer.
    static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (line.length() == 65_536) throw new IOException("Message exceeds 65536 characters");
            line.append((char) ch);
        }
        if (!line.isEmpty()) throw new EOFException("Unterminated message");
        return null;
    }
}

//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
// RoutingController
// Student work: coordinate topology events, route computation, and publication.
//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
final class RoutingController {
    private final TopologyGraph graph = new TopologyGraph();
    private final DijkstraSolver solver = new DijkstraSolver();
    private final ForwardingTableBuilder builder = new ForwardingTableBuilder();
    private final ForwardingStore forwarding = new ForwardingStore();
    private long generation;
    private boolean initialized;

    // One ordered processing path: no overlapping route computations or late stale publications.
    /** TODO: accept INIT once; reject link changes before initialization.
     * Apply the event through graph, capture one snapshot, and compute/build all
     * root tables from that snapshot. Publish one complete ForwardingSnapshot.
     * Accepted events advance generation once, even for repeated link states;
     * rejected events leave published state and generation unchanged.
     * Return AuditRecord with event ID/type, new generation, and nanoTime readings
     * at acceptance, computation start, computation finish, and after publication.
     * Preserve this synchronized boundary. Do not add computation workers.
     * See the linked HTML prompt in Canvas Modules for the full contract.
     */
    synchronized AuditRecord handle(TopologyEvent event) {
        throw new UnsupportedOperationException("TODO: implement RoutingController.handle");
    }

    ForwardingSnapshot current() { return forwarding.current(); }
}

//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
// ForwardingTableBuilder
// Student work: turn a rooted predecessor tree into first-hop forwarding entries.
//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
final class ForwardingTableBuilder {
    /** TODO: build a fresh unmodifiable table for paths.source().
     * Inputs are a graph snapshot and the valid solver result for that snapshot.
     * Enumerate all known destinations, not just reachable distance-map keys.
     * Distances omit unreachable routers; predecessors omit both unreachable
     * routers and the source. Derive first hops from predecessor chains.
     * Self: (source, 0L, true). Unreachable: RouteEntry.unreachable().
     * Reachable non-self: first hop, solver's Long total cost, true.
     * Do not mutate the inputs or previous results. See the P3 Prompt in Modules.
     */
    Map<String, RouteEntry> build(GraphSnapshot topology, ShortestPathResult paths) {
        Map<String, RouteEntry> table = new TreeMap<>();
        String source = paths.source();

        for (String dest: topology.adjacency().keySet()) {
            if (dest.equals(source)) {
                table.put(dest, new RouteEntry(source, 0L, true)); 
            } else if (!paths.distances().containsKey(dest)) {
                table.put(dest, RouteEntry.unreachable());
            } else {
                String hop = dest;
                while (!paths.predecessors().get(hop).equals(source)) {
                    hop = paths.predecessors().get(hop);
                }
                table.put(dest, new RouteEntry(hop, paths.distances().get(dest), true));
            }
        }

        return Collections.unmodifiableMap(table);
    }
}

sealed interface TopologyEvent permits InitialTopology, LinkChange {
    String id();
    String type();

    static TopologyEvent decode(String id, String type, Map<String, Object> request) {
        if (type.equals("INIT")) {
            List<String> nodes = new ArrayList<>();
            for (Object node : Fields.array(request, "nodes")) nodes.add(Fields.identifier(node));
            List<Link> links = new ArrayList<>();
            for (Object value : Fields.array(request, "links")) {
                Map<String, Object> link = Json.object(value);
                links.add(new Link(Fields.text(link, "a"), Fields.text(link, "b"), Fields.cost(link)));
            }
            return new InitialTopology(id, nodes, links);
        }
        return new LinkChange(id, Fields.text(request, "a"), Fields.text(request, "b"),
                type.equals("LINK_UP"), type.equals("LINK_UP") ? Fields.cost(request) : 0);
    }
}

record InitialTopology(String id, List<String> nodes, List<Link> links) implements TopologyEvent {
    InitialTopology { nodes = List.copyOf(nodes); links = List.copyOf(links); }
    public String type() { return "INIT"; }
}
record LinkChange(String id, String a, String b, boolean up, long cost) implements TopologyEvent {
    public String type() { return up ? "LINK_UP" : "LINK_DOWN"; }
}
record Link(String a, String b, long cost) {}
record GraphSnapshot(Map<String, Map<String, Long>> adjacency) {
    GraphSnapshot {
        Map<String, Map<String, Long>> copy = new TreeMap<>();
        adjacency.forEach((node, edges) -> copy.put(node, Collections.unmodifiableMap(new TreeMap<>(edges))));
        adjacency = Collections.unmodifiableMap(copy);
    }
}

/** Provided graph: known physical links remain stored when unavailable. */
final class TopologyGraph {
    private record Edge(long cost, boolean active) {}
    private Map<String, Map<String, Edge>> nodes = new TreeMap<>();

    synchronized void initialize(List<String> ids, List<Link> links) {
        if (ids.isEmpty() || ids.size() > 64) throw new IllegalArgumentException("Require 1..64 routers");
        Map<String, Map<String, Edge>> candidate = new TreeMap<>();
        for (String id : ids) {
            Fields.identifier(id);
            if (candidate.put(id, new TreeMap<>()) != null) throw new IllegalArgumentException("Duplicate router");
        }
        for (Link link : links) {
            validateEndpoints(candidate, link.a(), link.b());
            Fields.validCost(link.cost());
            if (candidate.get(link.a()).containsKey(link.b())) throw new IllegalArgumentException("Duplicate link");
            candidate.get(link.a()).put(link.b(), new Edge(link.cost(), true));
            candidate.get(link.b()).put(link.a(), new Edge(link.cost(), true));
        }
        nodes = candidate; // Invalid initialization cannot leave a partially changed graph.
    }

    synchronized void change(String a, String b, boolean up, long cost) {
        validateEndpoints(nodes, a, b);
        Edge previous = nodes.get(a).get(b);
        if (previous == null) throw new IllegalArgumentException("Unknown link");
        if (up) Fields.validCost(cost);
        Edge next = new Edge(up ? cost : previous.cost(), up);
        nodes.get(a).put(b, next); nodes.get(b).put(a, next);
    }

    private static void validateEndpoints(Map<String, ?> nodes, String a, String b) {
        if (a.equals(b) || !nodes.containsKey(a) || !nodes.containsKey(b)) {
            throw new IllegalArgumentException("Require distinct known link endpoints");
        }
    }

    synchronized GraphSnapshot snapshot() {
        Map<String, Map<String, Long>> adjacency = new TreeMap<>();
        nodes.forEach((id, edges) -> {
            Map<String, Long> active = new TreeMap<>();
            edges.forEach((neighbor, edge) -> { if (edge.active()) active.put(neighbor, edge.cost()); });
            adjacency.put(id, active);
        });
        return new GraphSnapshot(adjacency);
    }
}

/** Provided solver. Positive weights; equal costs select the lexicographically first next hop. */
final class DijkstraSolver {
    private record Candidate(String node, long distance, String firstHop) {}

    ShortestPathResult compute(GraphSnapshot snapshot, String source) {
        Map<String, Map<String, Long>> graph = snapshot.adjacency();
        if (!graph.containsKey(source)) throw new IllegalArgumentException("Unknown source");
        Map<String, Long> distances = new HashMap<>();
        Map<String, String> hops = new HashMap<>();
        Map<String, String> predecessors = new HashMap<>();
        PriorityQueue<Candidate> queue = new PriorityQueue<>(Comparator.comparingLong(Candidate::distance)
                .thenComparing(Candidate::firstHop).thenComparing(Candidate::node));
        distances.put(source, 0L); hops.put(source, source);
        queue.add(new Candidate(source, 0, source));
        while (!queue.isEmpty()) {
            Candidate current = queue.remove();
            if (current.distance() != distances.get(current.node()) ||
                    !current.firstHop().equals(hops.get(current.node()))) continue;
            for (var edge : graph.get(current.node()).entrySet()) {
                long distance = current.distance() + edge.getValue();
                String hop = current.node().equals(source) ? edge.getKey() : current.firstHop();
                Long old = distances.get(edge.getKey());
                if (old == null || distance < old ||
                        (distance == old && hop.compareTo(hops.get(edge.getKey())) < 0)) {
                    distances.put(edge.getKey(), distance); hops.put(edge.getKey(), hop);
                    predecessors.put(edge.getKey(), current.node());
                    queue.add(new Candidate(edge.getKey(), distance, hop));
                }
            }
        }
        return new ShortestPathResult(source, distances, predecessors);
    }
}

/** Provided result: distances include only reachable nodes (source has distance zero).
 * Predecessors include reachable non-source nodes only. Positive costs make the
 * predecessor chain terminate at source. Results belong to the input snapshot. */
record ShortestPathResult(String source, Map<String, Long> distances,
                          Map<String, String> predecessors) {
    ShortestPathResult {
        distances = Map.copyOf(distances);
        predecessors = Map.copyOf(predecessors);
    }
}

record RouteEntry(String nextHop, Long cost, boolean reachable) {
    static RouteEntry unreachable() { return new RouteEntry(null, null, false); }
    Map<String, Object> wire() { return Fields.map("nextHop", nextHop, "cost", cost, "reachable", reachable); }
}
record ForwardingSnapshot(long generation, Map<String, Map<String, RouteEntry>> tables) {
    ForwardingSnapshot {
        Map<String, Map<String, RouteEntry>> copy = new TreeMap<>();
        tables.forEach((root, routes) -> copy.put(root, Collections.unmodifiableMap(new TreeMap<>(routes))));
        tables = Collections.unmodifiableMap(copy);
    }
    RouteEntry route(String from, String to) {
        if (!tables.containsKey(from) || !tables.get(from).containsKey(to)) {
            throw new IllegalArgumentException("Unknown router in query");
        }
        return tables.get(from).get(to);
    }
    Map<String, Object> wireTables() {
        Map<String, Object> result = new TreeMap<>();
        tables.forEach((root, routes) -> {
            Map<String, Object> entries = new TreeMap<>();
            routes.forEach((destination, route) -> entries.put(destination, route.wire()));
            result.put(root, entries);
        });
        return result;
    }
}
final class ForwardingStore {
    private final AtomicReference<ForwardingSnapshot> active = new AtomicReference<>();
    void publish(ForwardingSnapshot snapshot) { active.set(snapshot); }
    ForwardingSnapshot current() {
        ForwardingSnapshot snapshot = active.get();
        if (snapshot == null) throw new IllegalStateException("Topology not initialized");
        return snapshot;
    }
}
record AuditRecord(String id, String eventType, long generation, long acceptedNanos,
                   long computeStartedNanos, long computeFinishedNanos, long publishedNanos) {
    Map<String, Object> wire() {
        return Fields.map("type", "ACK", "id", id, "eventType", eventType, "generation", generation,
                "acceptedNanos", acceptedNanos, "computeStartedNanos", computeStartedNanos,
                "computeFinishedNanos", computeFinishedNanos, "publishedNanos", publishedNanos,
                "elapsedNanos", publishedNanos - acceptedNanos);
    }
}

final class Fields {
    static String identifier(Object value) {
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Identifiers require 1..64 ASCII letters, digits, '_' or '-'");
        }
        return text;
    }
    static String text(Map<String, Object> map, String key) { return identifier(map.get(key)); }
    static List<Object> array(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof List<?> list)) throw new IllegalArgumentException("Expected array: " + key);
        return new ArrayList<>(list);
    }
    static long cost(Map<String, Object> map) {
        if (!(map.get("cost") instanceof Long cost)) throw new IllegalArgumentException("Expected integer cost");
        validCost(cost); return cost;
    }
    static void validCost(long cost) {
        if (cost < 1 || cost > 1_000_000_000L) throw new IllegalArgumentException("Cost must be 1..1000000000");
    }
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}

/** Dependency-free JSON codec: objects, arrays, strings, booleans, null, and signed long integers.
 * Decimal/exponent numbers are deliberately outside the P3 wire contract. */
final class Json {
    private final String source;
    private int pos;
    private Json(String source) { this.source = source; }
    static Object parse(String source) {
        Json parser = new Json(source);
        Object result = parser.value(0);
        parser.space();
        if (parser.pos != source.length()) throw parser.error();
        return result;
    }
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }
    private IllegalArgumentException error() { return new IllegalArgumentException("Invalid JSON at character " + pos); }
    private void space() { while (pos < source.length() && " \t\r\n".indexOf(source.charAt(pos)) >= 0) pos++; }
    private boolean eat(char c) {
        space();
        if (pos < source.length() && source.charAt(pos) == c) { pos++; return true; }
        return false;
    }
    private Object value(int depth) {
        if (depth > 16) throw new IllegalArgumentException("JSON nesting exceeds 16");
        space();
        if (pos == source.length()) throw error();
        char c = source.charAt(pos);
        if (c == '"') return string();
        if (eat('{')) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (eat('}')) return map;
            do {
                space(); String key = string();
                if (!eat(':') || map.containsKey(key)) throw error();
                map.put(key, value(depth + 1));
            } while (eat(','));
            if (!eat('}')) throw error(); return map;
        }
        if (eat('[')) {
            List<Object> list = new ArrayList<>();
            if (eat(']')) return list;
            do { list.add(value(depth + 1)); } while (eat(','));
            if (!eat(']')) throw error(); return list;
        }
        for (String literal : List.of("true", "false", "null")) {
            if (source.startsWith(literal, pos)) {
                pos += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = pos;
        if (c == '-') pos++;
        if (pos == source.length() || !digit(source.charAt(pos))) throw error();
        if (source.charAt(pos++) != '0') while (pos < source.length() && digit(source.charAt(pos))) pos++;
        try { return Long.parseLong(source.substring(start, pos)); }
        catch (NumberFormatException e) { throw error(); }
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private String string() {
        if (pos == source.length() || source.charAt(pos++) != '"') throw error();
        StringBuilder result = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos++);
            if (c == '"') return result.toString();
            if (c < 32) throw error();
            if (c == '\\') {
                if (pos == source.length()) throw error();
                c = source.charAt(pos++);
                switch (c) {
                    case '"', '\\', '/' -> result.append(c);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (pos + 4 > source.length()) throw error();
                        for (int i = pos; i < pos + 4; i++) {
                            if ("0123456789abcdefABCDEF".indexOf(source.charAt(i)) < 0) throw error();
                        }
                        try { result.append((char) Integer.parseInt(source.substring(pos, pos + 4), 16)); }
                        catch (NumberFormatException e) { throw error(); }
                        pos += 4;
                    }
                    default -> throw error();
                }
            } else result.append(c);
        }
        throw error();
    }
    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof String text) {
            StringBuilder result = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                if (c == '"' || c == '\\') result.append('\\').append(c);
                else if (c < 32) result.append(String.format("\\u%04x", (int) c));
                else result.append(c);
            }
            return result.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringJoiner result = new StringJoiner(",", "{", "}");
            map.forEach((key, entry) -> result.add(write((String) key) + ":" + write(entry)));
            return result.toString();
        }
        if (value instanceof Iterable<?> list) {
            StringJoiner result = new StringJoiner(",", "[", "]");
            list.forEach(entry -> result.add(write(entry))); return result.toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}

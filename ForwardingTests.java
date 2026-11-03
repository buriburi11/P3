import java.util.*;

/** P3 focused student tests. Place beside cpe.java, then run:
 * javac --release 21 cpe.java ForwardingTests.java
 * java ForwardingTests
 * These tests exercise forwarding construction, not the complete controller.
 */
public class ForwardingTests {
    private static int passed;
    private static int total;

    public static void main(String[] args) {
        run("self route", () -> {
            Map<String, RouteEntry> table = build(List.of("S"),
                    Map.of("S", 0L), Map.of());
            expect("S to S", new RouteEntry("S", 0L, true), table.get("S"));
        });
        run("direct neighbor", () -> {
            Map<String, RouteEntry> table = build(List.of("S", "N"),
                    Map.of("S", 0L, "N", 2L), Map.of("N", "S"));
            expect("S to N", new RouteEntry("N", 2L, true), table.get("N"));
        });
        run("multi-hop route", () -> {
            Map<String, RouteEntry> table = build(List.of("S", "N", "M", "D"),
                    Map.of("S", 0L, "N", 2L, "M", 5L, "D", 9L),
                    Map.of("N", "S", "M", "N", "D", "M"));
            expect("S to D", new RouteEntry("N", 9L, true), table.get("D"));
            expect("S to M", new RouteEntry("N", 5L, true), table.get("M"));
        });
        run("unreachable destination", () -> {
            Map<String, RouteEntry> table = build(List.of("S", "U"),
                    Map.of("S", 0L), Map.of());
            expect("S to U", RouteEntry.unreachable(), table.get("U"));
        });
        run("complete table", () -> {
            Map<String, RouteEntry> table = build(List.of("S", "N", "U"),
                    Map.of("S", 0L, "N", 2L), Map.of("N", "S"));
            expect("destination set", Set.of("S", "N", "U"), table.keySet());
        });
        run("selected equal-cost predecessor tree", () -> {
            GraphSnapshot topology = new GraphSnapshot(Map.of(
                    "S", Map.of("B", 2L, "C", 1L),
                    "B", Map.of("S", 2L, "D", 2L),
                    "C", Map.of("S", 1L, "D", 3L),
                    "D", Map.of("B", 2L, "C", 3L)));
            // Both paths cost 4. The supplied solver has selected first hop B.
            ShortestPathResult paths = new ShortestPathResult("S",
                    Map.of("S", 0L, "B", 2L, "C", 1L, "D", 4L),
                    Map.of("B", "S", "C", "S", "D", "B"));
            Map<String, RouteEntry> table = new ForwardingTableBuilder().build(topology, paths);
            expect("selected S to D", new RouteEntry("B", 4L, true), table.get("D"));
        });
        run("large path cost", () -> {
            Map<String, RouteEntry> table = build(List.of("S", "N", "M", "D"),
                    Map.of("S", 0L, "N", 1_000_000_000L, "M", 2_000_000_000L, "D", 3_000_000_000L),
                    Map.of("N", "S", "M", "N", "D", "M"));
            expect("S to D", new RouteEntry("N", 3_000_000_000L, true), table.get("D"));
        });
        run("unmodifiable result", () -> {
            Map<String, RouteEntry> table = build(List.of("S"), Map.of("S", 0L), Map.of());
            try {
                table.put("extra", RouteEntry.unreachable());
            } catch (UnsupportedOperationException expected) {
                return;
            }
            throw new AssertionError("expected an unmodifiable table; put succeeded");
        });
        run("rebuild without obsolete routes", () -> {
            Map<String, RouteEntry> before = build(List.of("S", "N", "D"),
                    Map.of("S", 0L, "N", 2L, "D", 5L), Map.of("N", "S", "D", "N"));
            Map<String, RouteEntry> failed = build(List.of("S", "N", "D"),
                    Map.of("S", 0L, "N", 2L), Map.of("N", "S"));
            Map<String, RouteEntry> restored = build(List.of("S", "N", "D"),
                    Map.of("S", 0L, "N", 2L, "D", 9L), Map.of("N", "S", "D", "N"));
            expect("failed S to D", RouteEntry.unreachable(), failed.get("D"));
            expect("restored S to D", new RouteEntry("N", 9L, true), restored.get("D"));
            expect("previous table remains unchanged", new RouteEntry("N", 5L, true), before.get("D"));
        });
        System.out.println(passed + "/" + total + " cases passed");
        if (passed != total) System.exit(1);
    }

    private static Map<String, RouteEntry> build(List<String> nodes, Map<String, Long> distances,
                                                Map<String, String> predecessors) {
        // Reconstruct a matching tree topology without invoking Dijkstra or sockets.
        Map<String, Map<String, Long>> adjacency = new TreeMap<>();
        for (String node : nodes) adjacency.put(node, new TreeMap<>());
        predecessors.forEach((node, parent) -> {
            long cost = distances.get(node) - distances.get(parent);
            adjacency.get(parent).put(node, cost);
            adjacency.get(node).put(parent, cost);
        });
        return new ForwardingTableBuilder().build(new GraphSnapshot(adjacency),
                new ShortestPathResult("S", distances, predecessors));
    }

    private static void expect(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void run(String name, Runnable test) {
        total++;
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (AssertionError | Exception failure) {
            System.out.println("FAIL  " + name + ": " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }
}

#!/usr/bin/env python3
"""P3 student simulator. Start a fresh engine, then run:
python3 TopologySimulator.py --port 8080
Find the P3 Prompt and Tutorial under Modules in Canvas.
"""
import argparse
import contextlib
import json
import socket
import sys


class Wire:
    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.stream = self.socket.makefile("rwb")

    def close(self):
        self.stream.close()
        self.socket.close()

    def raw(self, text):
        self.stream.write(text.encode("utf-8") + b"\n")
        self.stream.flush()
        line = self.stream.readline(1_000_001)
        if not line:
            raise AssertionError("Engine closed without a response; inspect engine stderr for a TODO or exception")
        if not line.endswith(b"\n") or len(line) > 1_000_000:
            raise AssertionError("Missing newline or oversized response")
        value = json.loads(line)
        if not isinstance(value, dict):
            raise AssertionError("Response must be a JSON object")
        return value

    def request(self, message, expected):
        response = self.raw(json.dumps(message))
        if response.get("id") != message["id"] or response.get("type") != expected:
            raise AssertionError(f"Request {message['id']}: expected {expected}, got {response}")
        return response


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def oracle(nodes, links):
    """Independent Floyd-Warshall distances followed by first-hop selection."""
    inf = float("inf")
    distance = {a: {b: 0 if a == b else inf for b in nodes} for a in nodes}
    adjacency = {a: {} for a in nodes}
    for (a, b), (cost, active) in links.items():
        if active:
            distance[a][b] = distance[b][a] = cost
            adjacency[a][b] = adjacency[b][a] = cost
    for k in nodes:
        for a in nodes:
            for b in nodes:
                distance[a][b] = min(distance[a][b], distance[a][k] + distance[k][b])
    result = {}
    for a in nodes:
        result[a] = {}
        for b in nodes:
            cost = distance[a][b]
            reachable = cost != inf
            hop = None
            if a == b:
                hop = a
            elif reachable:
                hop = min(n for n, weight in adjacency[a].items() if weight + distance[n][b] == cost)
            result[a][b] = {"nextHop": hop, "cost": cost if reachable else None, "reachable": reachable}
    return result


def ack(wire, message, generation):
    response = wire.request(message, "ACK")
    require(response.get("generation") == generation, f"Expected generation {generation}: {response}")
    require(response.get("eventType") == message["type"], f"Wrong event type: {response}")
    stamps = [response.get(k) for k in ("acceptedNanos", "computeStartedNanos", "computeFinishedNanos", "publishedNanos")]
    require(all(type(x) is int for x in stamps), f"Missing or invalid timestamps: {response}")
    require(stamps == sorted(stamps), f"Timestamps out of order: {response}")
    require(response.get("elapsedNanos") == stamps[-1] - stamps[0], f"Wrong elapsed interval: {response}")


def verify(wire, nodes, links, generation):
    expected = oracle(nodes, links)
    snapshot = wire.request({"id": "snapshot", "type": "SNAPSHOT"}, "SNAPSHOT")
    require(snapshot.get("generation") == generation, f"Expected generation {generation}: {snapshot}")
    tables = snapshot.get("tables")
    if tables != expected:
        if isinstance(tables, dict):
            for a in nodes:
                for b in nodes:
                    actual = tables.get(a, {})
                    actual = actual.get(b) if isinstance(actual, dict) else actual
                    require(actual == expected[a][b],
                            f"Generation {generation}, {a}->{b}: expected {expected[a][b]}, got {actual}")
        raise AssertionError(f"Generation {generation}: unexpected or missing tables/destinations")
    queries = [{"from": a, "to": b} for a in nodes for b in nodes]
    response = wire.request({"id": "query", "type": "QUERY", "queries": queries}, "ROUTES")
    require(response.get("generation") == generation, f"Query returned wrong generation: {response}")
    wanted = [dict(expected[q["from"]][q["to"]], **q) for q in queries]
    require(response.get("routes") == wanted, "Query routes differ from expected entries (including order/count)")


def baseline(port):
    nodes = [f"R{i}" for i in range(1, 6)]
    links = {("R1", "R2"): (10, True), ("R1", "R4"): (5, True),
             ("R2", "R3"): (20, True), ("R3", "R5"): (5, True), ("R4", "R5"): (15, True)}
    stage = "connect to engine"
    try:
        with contextlib.closing(Wire(port)) as wire:
            stage = "pre-initialization infrastructure checks"
            wire.request({"id": "early", "type": "SNAPSHOT"}, "ERROR")
            require(wire.raw("{broken").get("type") == "ERROR", "Malformed JSON was not rejected")
            stage = "reject invalid initialization"
            wire.request({"id": "invalid-init", "type": "INIT", "nodes": ["A", "A"], "links": []}, "ERROR")
            stage = "initialize and publish generation 1"
            ack(wire, {"id": "init", "type": "INIT", "nodes": nodes,
                       "links": [{"a": a, "b": b, "cost": cost} for (a, b), (cost, _) in links.items()]}, 1)
            verify(wire, nodes, links, 1)
            print("PASS  initial tables (generation 1)")
            steps = [("R4", "R5", False, 0), ("R2", "R3", False, 0),
                     ("R5", "R4", True, 15), ("R2", "R3", True, 20),
                     ("R2", "R3", True, 20), ("R4", "R5", True, 40)]
            for generation, (a, b, up, cost) in enumerate(steps, 2):
                stage = f"generation {generation}: {a}-{b} {'up' if up else 'down'}"
                message = {"id": f"event-{generation}", "type": "LINK_UP" if up else "LINK_DOWN", "a": a, "b": b}
                if up:
                    message["cost"] = cost
                ack(wire, message, generation)
                key = (a, b) if (a, b) in links else (b, a)
                old_cost, _ = links[key]
                links[key] = (cost if up else old_cost, up)
                verify(wire, nodes, links, generation)
                print(f"PASS  {stage}")
            stage = "reject invalid requests without changing generation 7"
            for message in [
                {"type": "LINK_UP", "a": "R1", "b": "R2", "cost": 0},
                {"type": "LINK_UP", "a": "R1", "b": "R2", "cost": True},
                {"type": "LINK_DOWN", "a": "R1", "b": "R3"},
                {"type": "LINK_DOWN", "a": "R1", "b": "unknown"},
                {"type": "INIT", "nodes": ["Z"], "links": []},
                {"type": "QUERY", "queries": [{"from": "R1", "to": "unknown"}]},
                {"type": "UNKNOWN"},
            ]:
                wire.request(dict(message, id="invalid"), "ERROR")
                verify(wire, nodes, links, 7)
            print("PASS  rejection preserves installed state")
            stage = "fragmented and combined TCP messages"
            payload = b'{"id":"fragment","type":"SNAPSHOT"}\n'
            wire.stream.write(payload[:9]); wire.stream.flush()
            wire.stream.write(payload[9:] + b'{"id":"next","type":"SNAPSHOT"}\n'); wire.stream.flush()
            for expected_id in ("fragment", "next"):
                response = json.loads(wire.stream.readline(1_000_001))
                require(response.get("type") == "SNAPSHOT" and response.get("id") == expected_id
                        and response.get("generation") == 7 and response.get("tables") == oracle(nodes, links),
                        f"Wrong framed snapshot: {response}")
    except (AssertionError, OSError, ValueError, KeyError, TypeError) as failure:
        raise RuntimeError(f"{stage}: {failure}") from failure
    print("TCP baseline, failure, partition, recovery, rejection, and framing: PASS")


def main():
    parser = argparse.ArgumentParser(description="Check a fresh P3 engine. Stops at the first failed transition.")
    parser.add_argument("--port", type=int, default=8080, help="Engine TCP port (default 8080)")
    args = parser.parse_args()
    try:
        baseline(args.port)
    except RuntimeError as failure:
        print(f"FAIL  {failure}", file=sys.stderr)
        print("Fix the first failure; restart the engine before another run.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

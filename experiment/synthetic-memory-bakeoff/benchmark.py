import json
import math
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

NO_DECISION_STATUSES = {"undecided", "mentioned_not_evaluated", "unknown", "undecided_with_new_fact"}


def request_json(url, payload=None, headers=None, method=None, timeout=180):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req_headers = {"Accept": "application/json"}
    if data is not None:
        req_headers["Content-Type"] = "application/json"
    if headers:
        req_headers.update(headers)
    request = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        body = error.read()
        status = error.code
    elapsed_ms = (time.perf_counter() - started) * 1000
    try:
        parsed = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        parsed = {"raw_body": body[:500].decode("utf-8", errors="replace")}
    return {"http_status": status, "elapsed_ms": elapsed_ms, "body": parsed}


def successful(response):
    body = response["body"]
    return response["http_status"] < 400 and isinstance(body, dict) and body.get("code", 0) in (0, "0", 200, "200")


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(fraction * len(ordered)) - 1)], 2)


def stats(values):
    return {"count": len(values), "p50_ms": percentile(values, 0.50), "p95_ms": percentile(values, 0.95), "max_ms": round(max(values), 2) if values else None}


def flatten(value):
    if isinstance(value, dict):
        parts = []
        for key, item in value.items():
            parts.append(str(key))
            parts.append(flatten(item))
        return " ".join(parts)
    if isinstance(value, list):
        return " ".join(flatten(item) for item in value)
    if isinstance(value, str):
        return value
    return str(value)


def walk_key(value, key):
    if isinstance(value, dict):
        if key in value:
            yield value[key]
        for item in value.values():
            yield from walk_key(item, key)
    elif isinstance(value, list):
        for item in value:
            yield from walk_key(item, key)


def layer_counts(base, headers):
    paths = {"L1_atomic": "/v3/atomic/count", "L2_scenario": "/v3/scenario/count", "L3_core": "/v3/core/count"}
    counts = {}
    for name, path in paths.items():
        response = request_json(f"{base}{path}", {}, headers, timeout=60)
        counts[name] = {"http_status": response["http_status"], "data": response["body"].get("data", {})}
    return counts


def number_of_records(counts, name):
    data = counts.get(name, {}).get("data", {})
    return data.get("total", 0) if isinstance(data, dict) else 0


def restart_container(name):
    started = time.perf_counter()
    subprocess.run(["docker", "restart", name], check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return round((time.perf_counter() - started) * 1000, 2)


def write_result(path, result):
    with open(path, "w", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")


def memos_headers():
    return {}


def run_memos(corpus, base, result_dir):
    user_id = "root"
    cube_id = "synthetic-bakeoff-cube"
    base = base.rstrip("/")
    result = {"engine": "MemOS self-host REST", "writes": [], "searches": [], "failures": []}
    health = request_json(f"{base}/health", method="GET", timeout=15)
    result["health"] = health
    if not successful(health):
        raise RuntimeError("MemOS health check failed")
    created = request_json(f"{base}/product/create_cube", {"cube_name": "Synthetic bakeoff", "owner_id": user_id, "cube_id": cube_id}, memos_headers())
    result["cube"] = created["body"]
    if not successful(created):
        raise RuntimeError("MemOS synthetic cube creation failed")
    for case in corpus["cases"]:
        messages = [{"role": item["role"], "content": item["content"], "chat_time": item["timestamp"]} for item in case["messages"]]
        payload = {
            "user_id": user_id,
            "writable_cube_ids": [cube_id],
            "session_id": case["source_chat_id"],
            "messages": messages,
            "async_mode": "async",
            "custom_tags": ["synthetic-only", case["language"], case["project"]],
            "info": {
                "source_type": "synthetic_conversation",
                "source_id": case["case_id"],
                "source_chat_id": case["source_chat_id"],
                "source_project": case["project"],
                "source_content": case["raw_transcript"],
            },
        }
        response = request_json(f"{base}/product/add", payload, memos_headers())
        item = {"case_id": case["case_id"], "http_status": response["http_status"], "elapsed_ms": response["elapsed_ms"], "body": response["body"]}
        result["writes"].append(item)
        if not successful(response):
            result["failures"].append({"stage": "write", "case_id": case["case_id"], "http_status": response["http_status"]})
    wait_query = urllib.parse.urlencode({"user_name": user_id, "timeout_seconds": 300, "poll_interval": 1})
    result["async_wait"] = request_json(f"{base}/product/scheduler/wait?{wait_query}", {}, memos_headers(), method="POST", timeout=330)
    if not successful(result["async_wait"]):
        result["failures"].append({"stage": "async_wait", "http_status": result["async_wait"]["http_status"]})
    compose_file = os.environ.get("MEMOS_COMPOSE_FILE")
    compose_env_file = os.environ.get("MEMOS_COMPOSE_ENV_FILE")
    compose_project = os.environ.get("MEMOS_COMPOSE_PROJECT", "bakeoff-memos")
    if compose_file:
        started = time.perf_counter()
        command = ["docker", "compose"]
        if compose_env_file:
            command.extend(["--env-file", compose_env_file])
        command.extend(["-p", compose_project, "-f", compose_file, "restart", "memos"])
        subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        result["restart"] = {"elapsed_ms": round((time.perf_counter() - started) * 1000, 2)}
        time.sleep(3)
        result["restart"]["health"] = request_json(f"{base}/health", method="GET", timeout=20)
    all_memories = request_json(
        f"{base}/product/get_all",
        {"user_id": user_id, "mem_cube_ids": [cube_id], "memory_type": "text_mem"},
        memos_headers(),
        timeout=180,
    )
    result["archive_read"] = {"http_status": all_memories["http_status"], "elapsed_ms": all_memories["elapsed_ms"]}
    archive_text = flatten(all_memories["body"])
    raw_checks = []
    for case in corpus["cases"]:
        matching = [value for value in walk_key(all_memories["body"], "source_content") if isinstance(value, str) and value == case["raw_transcript"]]
        raw_checks.append({"case_id": case["case_id"], "raw_sha256": case["raw_sha256"], "exact_raw_source_content_retrievable": bool(matching)})
    result["raw_integrity"] = {"cases": len(raw_checks), "exact_transcripts_retrieved": sum(row["exact_raw_source_content_retrievable"] for row in raw_checks), "case_checks": raw_checks}
    if result["raw_integrity"]["exact_transcripts_retrieved"] != len(corpus["cases"]):
        result["failures"].append({"stage": "raw_integrity", "exact": result["raw_integrity"]["exact_transcripts_retrieved"], "expected": len(corpus["cases"])})
    result["archive_contains_source_ids"] = sum(case["case_id"] in archive_text for case in corpus["cases"])
    result["no_decision_cases"] = [case["case_id"] for case in corpus["cases"] if case["status"] in NO_DECISION_STATUSES]
    result["no_decision_source_status_retrieved"] = sum(
        any(marker in flatten(all_memories["body"]).casefold() for marker in ("no decision", "no recommendation", "решение не принято", "рекомендации нет"))
        for case in corpus["cases"] if case["case_id"] in result["no_decision_cases"]
    )
    known_products = sorted({product.strip() for case in corpus["cases"] for product in case["product"].split(",") if product.strip()}, key=str.casefold)
    first_read = None
    if corpus["cases"]:
        first_case = corpus["cases"][0]
        first_read = request_json(
            f"{base}/product/search",
            {"query": first_case["query"], "user_id": user_id, "readable_cube_ids": [cube_id], "mode": "fast", "top_k": 20, "relativity": 0, "rerank": False, "dedup": "no"},
            memos_headers(), timeout=180,
        )
    result["first_read_after_restart"] = {"http_status": first_read["http_status"], "latency_ms": first_read["elapsed_ms"]} if first_read else None
    queries = []
    for case in corpus["cases"]:
        search = request_json(
            f"{base}/product/search",
            {"query": case["query"], "user_id": user_id, "readable_cube_ids": [cube_id], "mode": "fast", "top_k": 20, "relativity": 0, "rerank": False, "dedup": "no"},
            memos_headers(),
            timeout=180,
        )
        response_text = flatten(search["body"])
        hits = [fact for fact in case["required_facts"] if fact.casefold() in response_text.casefold()]
        source_found = case["case_id"] in response_text or case["source_chat_id"] in response_text
        expected_products = {product.strip().casefold() for product in case["product"].split(",")}
        unexpected_products = [product for product in known_products if product.casefold() not in expected_products and product.casefold() in response_text.casefold()]
        raw_values = [value for value in walk_key(search["body"], "source_content") if isinstance(value, str)]
        exact_raw = case["raw_transcript"] in raw_values
        queries.append({
            "case_id": case["case_id"],
            "language": case["language"],
            "query_chat_id": case["separate_query_chat_id"],
            "query": case["query"],
            "http_status": search["http_status"],
            "latency_ms": search["elapsed_ms"],
            "source_found": source_found,
            "citation_source_id_found": case["case_id"] in response_text,
            "exact_raw_transcript_retrieved": exact_raw,
            "required_fact_hits": hits,
            "required_fact_count": len(case["required_facts"]),
            "expected_status": case["status"],
            "no_decision_status_preserved": case["status"] not in NO_DECISION_STATUSES or any(marker in response_text.casefold() for marker in ("no decision", "no recommendation", "not evaluated", "решение не принято", "рекомендации нет", "не оценивали")),
            "unexpected_known_product_mentions": unexpected_products,
            "evidence": search["body"],
        })
        if not successful(search):
            result["failures"].append({"stage": "search", "case_id": case["case_id"], "http_status": search["http_status"]})
    result["searches"] = queries
    result["search_latency"] = stats([row["latency_ms"] for row in queries if row["http_status"] < 400])
    result["warm_search_latency"] = stats([row["latency_ms"] for row in queries[1:] if row["http_status"] < 400])
    result["recall_at_20"] = round(sum(row["source_found"] for row in queries) / max(1, len(queries)), 4)
    result["citation_rate"] = round(sum(row["citation_source_id_found"] for row in queries) / max(1, len(queries)), 4)
    result["exact_raw_search_rate"] = round(sum(row["exact_raw_transcript_retrieved"] for row in queries) / max(1, len(queries)), 4)
    result["required_fact_recall"] = round(sum(len(row["required_fact_hits"]) for row in queries) / max(1, sum(row["required_fact_count"] for row in queries)), 4)
    result["no_decision_status_recall"] = round(sum(row["no_decision_status_preserved"] for row in queries if row["expected_status"] in NO_DECISION_STATUSES) / max(1, sum(row["expected_status"] in NO_DECISION_STATUSES for row in queries)), 4)
    result["unexpected_product_mention_rate"] = round(sum(bool(row["unexpected_known_product_mentions"]) for row in queries) / max(1, len(queries)), 4)
    candidate_rows = [row for row in queries if row["case_id"] in {"case-003-en", "case-004-ru"}]
    result["five_candidate_batch_recall"] = round(sum(len(row["required_fact_hits"]) for row in candidate_rows) / max(1, sum(row["required_fact_count"] for row in candidate_rows)), 4)
    if result["no_decision_status_recall"] < 1:
        result["failures"].append({"stage": "no_decision_status", "recall": result["no_decision_status_recall"], "required": 1.0})
    result["language_metrics"] = {
        lang: {"cases": len([row for row in queries if row["language"] == lang]), "source_hits": sum(row["source_found"] for row in queries if row["language"] == lang), "fact_hits": sum(len(row["required_fact_hits"]) for row in queries if row["language"] == lang)}
        for lang in ("en", "ru")
    }
    result["raw_case_retrieval"] = [row for row in queries if row["case_id"] in {"case-001-en", "case-002-ru", "case-003-en", "case-004-ru"}]
    if not successful(all_memories):
        result["failures"].append({"stage": "archive_read", "http_status": all_memories["http_status"]})
    if result.get("restart") and not successful(result["restart"]["health"]):
        result["failures"].append({"stage": "restart_health", "http_status": result["restart"]["health"]["http_status"]})
    write_result(os.path.join(result_dir, "memos.json"), result)
    return result


def tencent_headers(identity, session_id=None):
    headers = {
        "x-tdai-service-id": "synthetic-bakeoff",
        "x-tdai-team-id": "synthetic-team",
        "x-tdai-agent-id": "synthetic-agent",
        "x-tdai-user-id": "synthetic-user",
    }
    if session_id:
        headers["x-tdai-session-id"] = session_id
    return headers


def run_tencent(corpus, base, result_dir):
    base = base.rstrip("/")
    result = {"engine": "TencentDB-Agent-Memory MemoryCore standalone", "writes": [], "raw_queries": [], "searches": [], "failures": []}
    health = request_json(f"{base}/health", method="GET", timeout=15)
    result["health"] = health
    if not successful(health):
        raise RuntimeError("TencentDB MemoryCore health check failed")
    fts_values = list(walk_key(health["body"], "ftsAvailable")) + list(walk_key(health["body"], "fts_available"))
    result["fts5_available"] = fts_values[-1] if fts_values else None
    if result["fts5_available"] is not True:
        raise RuntimeError("TencentDB reports FTS5 unavailable or does not expose a positive FTS5 health signal")
    identity = "synthetic-team/synthetic-agent/synthetic-user"
    for case in corpus["cases"]:
        headers = tencent_headers(identity, case["source_chat_id"])
        messages = [{"role": item["role"], "content": item["content"], "timestamp": item["timestamp"], "recorded_at": item["timestamp"]} for item in case["messages"]]
        payload = {"session_id": case["source_chat_id"], "messages": messages}
        response = request_json(f"{base}/v3/conversation/add", payload, headers)
        result["writes"].append({"case_id": case["case_id"], "http_status": response["http_status"], "elapsed_ms": response["elapsed_ms"], "body": response["body"]})
        if not successful(response):
            result["failures"].append({"stage": "write", "case_id": case["case_id"], "http_status": response["http_status"]})
    raw_cases = []
    for case in corpus["cases"]:
        response = request_json(
            f"{base}/v3/conversation/query",
            {"session_id": case["source_chat_id"], "limit": 100, "offset": 0},
            tencent_headers(identity, case["source_chat_id"]),
        )
        returned = response["body"].get("data", {}).get("messages", [])
        expected = case["messages"]
        exact = len(returned) == len(expected) and all(
            got.get("role") == want["role"] and got.get("content") == want["content"] and got.get("timestamp") == want["timestamp"]
            for got, want in zip(returned, expected)
        )
        row = {"case_id": case["case_id"], "source_chat_id": case["source_chat_id"], "expected_messages": len(expected), "returned_messages": len(returned), "exact_raw_integrity": exact, "http_status": response["http_status"], "source_turn_ids": [item.get("id") for item in returned], "messages": returned}
        raw_cases.append(row)
        result["raw_queries"].append(row)
        if not successful(response):
            result["failures"].append({"stage": "raw_query", "case_id": case["case_id"], "http_status": response["http_status"]})
    result["raw_integrity"] = {"cases": len(raw_cases), "exact_transcripts_retrieved": sum(row["exact_raw_integrity"] for row in raw_cases), "case_checks": result["raw_queries"]}
    if result["raw_integrity"]["exact_transcripts_retrieved"] != len(corpus["cases"]):
        result["failures"].append({"stage": "raw_integrity", "exact": result["raw_integrity"]["exact_transcripts_retrieved"], "expected": len(corpus["cases"])})
    layer_start = time.perf_counter()
    layer_history = []
    while time.perf_counter() - layer_start < 180:
        current = layer_counts(base, tencent_headers(identity, "lookup-layer-count"))
        layer_history.append({"elapsed_ms": round((time.perf_counter() - layer_start) * 1000, 2), "counts": current})
        for layer, data in current.items():
            if data["http_status"] >= 400:
                result["failures"].append({"stage": "async_layer_count", "layer": layer, "http_status": data["http_status"]})
        if all(number_of_records(current, name) > 0 for name in ("L1_atomic", "L2_scenario", "L3_core")):
            break
        time.sleep(3)
    result["async_layers"] = {"lag_ms": layer_history[-1]["elapsed_ms"] if layer_history else None, "history": layer_history, "completed": bool(layer_history and all(number_of_records(layer_history[-1]["counts"], name) > 0 for name in ("L1_atomic", "L2_scenario", "L3_core")))}
    result["layer_counts"] = layer_history[-1]["counts"] if layer_history else {}
    result["no_decision_cases"] = [case["case_id"] for case in corpus["cases"] if case["status"] in NO_DECISION_STATUSES]
    first_case = corpus["cases"][0]
    first_read = request_json(f"{base}/v3/conversation/search", {"query": first_case["query"], "limit": 20}, tencent_headers(identity, first_case["separate_query_chat_id"]), timeout=180)
    result["first_read_after_restart"] = {"http_status": first_read["http_status"], "latency_ms": first_read["elapsed_ms"]}
    result["restart"] = {"elapsed_ms": restart_container("tencent-memorycore")}
    time.sleep(3)
    result["restart"]["health"] = request_json(f"{base}/health", method="GET", timeout=20)
    searches = []
    for case in corpus["cases"]:
        response = request_json(
            f"{base}/v3/conversation/search",
            {"query": case["query"], "limit": 20},
            tencent_headers(identity, case["separate_query_chat_id"]),
            timeout=180,
        )
        body = response["body"]
        hits = body.get("data", {}).get("messages", [])
        text = flatten(hits)
        matching_ids = [hit.get("id") for hit in hits if any(message["content"] == hit.get("content") for message in case["messages"])]
        fact_hits = [fact for fact in case["required_facts"] if fact.casefold() in text.casefold()]
        found = bool(matching_ids) or case["product"].casefold() in text.casefold()
        row = {
            "case_id": case["case_id"],
            "language": case["language"],
            "query_chat_id": case["separate_query_chat_id"],
            "query": case["query"],
            "http_status": response["http_status"],
            "latency_ms": response["elapsed_ms"],
            "source_found": found,
            "citation_message_ids": matching_ids,
            "citation_source_chat_id": case["source_chat_id"] if matching_ids else None,
            "required_fact_hits": fact_hits,
            "required_fact_count": len(case["required_facts"]),
            "expected_status": case["status"],
            "no_decision_status_preserved": case["status"] not in NO_DECISION_STATUSES or any(marker in text.casefold() for marker in ("no decision", "no recommendation", "not evaluated", "решение не принято", "рекомендации нет", "не оценивали")),
            "evidence": hits,
        }
        searches.append(row)
        if not successful(response):
            result["failures"].append({"stage": "search", "case_id": case["case_id"], "http_status": response["http_status"]})
    result["searches"] = searches
    result["search_latency"] = stats([row["latency_ms"] for row in searches if row["http_status"] < 400])
    result["warm_search_latency"] = stats([row["latency_ms"] for row in searches[1:] if row["http_status"] < 400])
    result["recall_at_20"] = round(sum(row["source_found"] for row in searches) / max(1, len(searches)), 4)
    result["citation_rate"] = round(sum(bool(row["citation_message_ids"]) for row in searches) / max(1, len(searches)), 4)
    result["required_fact_recall"] = round(sum(len(row["required_fact_hits"]) for row in searches) / max(1, sum(row["required_fact_count"] for row in searches)), 4)
    result["no_decision_status_recall"] = round(sum(row["no_decision_status_preserved"] for row in searches if row["expected_status"] in NO_DECISION_STATUSES) / max(1, sum(row["expected_status"] in NO_DECISION_STATUSES for row in searches)), 4)
    known_products = sorted({product.strip() for case in corpus["cases"] for product in case["product"].split(",") if product.strip()}, key=str.casefold)
    for row in searches:
        case = next(case for case in corpus["cases"] if case["case_id"] == row["case_id"])
        expected_products = {product.strip().casefold() for product in case["product"].split(",")}
        response_text = flatten(row["evidence"]).casefold()
        row["unexpected_known_product_mentions"] = [product for product in known_products if product.casefold() not in expected_products and product.casefold() in response_text]
    result["unexpected_product_mention_rate"] = round(sum(bool(row["unexpected_known_product_mentions"]) for row in searches) / max(1, len(searches)), 4)
    candidate_rows = [row for row in searches if row["case_id"] in {"case-003-en", "case-004-ru"}]
    result["five_candidate_batch_recall"] = round(sum(len(row["required_fact_hits"]) for row in candidate_rows) / max(1, sum(row["required_fact_count"] for row in candidate_rows)), 4)
    if result["no_decision_status_recall"] < 1:
        result["failures"].append({"stage": "no_decision_status", "recall": result["no_decision_status_recall"], "required": 1.0})
    result["language_metrics"] = {
        lang: {"cases": len([row for row in searches if row["language"] == lang]), "source_hits": sum(row["source_found"] for row in searches if row["language"] == lang), "fact_hits": sum(len(row["required_fact_hits"]) for row in searches if row["language"] == lang)}
        for lang in ("en", "ru")
    }
    result["layer_counts_after_restart"] = layer_counts(base, tencent_headers(identity, "lookup-layer-count"))
    for layer, data in result["layer_counts_after_restart"].items():
        if data["http_status"] >= 400:
            result["failures"].append({"stage": "restart_layer_count", "layer": layer, "http_status": data["http_status"]})
    if not successful(result["restart"]["health"]):
        result["failures"].append({"stage": "restart_health", "http_status": result["restart"]["health"]["http_status"]})
    result["raw_case_retrieval"] = [row for row in searches if row["case_id"] in {"case-001-en", "case-002-ru", "case-003-en", "case-004-ru"}]
    result["raw_archive_samples"] = [row for row in raw_cases if row["case_id"] in {"case-001-en", "case-002-ru", "case-003-en", "case-004-ru"}]
    write_result(os.path.join(result_dir, "tencentdb.json"), result)
    return result


def main():
    engine = sys.argv[1]
    corpus_path = sys.argv[2]
    result_dir = sys.argv[3]
    os.makedirs(result_dir, exist_ok=True)
    with open(corpus_path, encoding="utf-8") as source:
        corpus = json.load(source)
    if not corpus.get("synthetic_only") or corpus.get("case_count") != 30:
        raise RuntimeError("Corpus guard failed: expected exactly 30 synthetic cases")
    if engine == "memos":
        result = run_memos(corpus, os.environ.get("MEMOS_URL", "http://127.0.0.1:8000"), result_dir)
    elif engine == "tencentdb":
        result = run_tencent(corpus, os.environ.get("TENCENTDB_URL", "http://127.0.0.1:8420"), result_dir)
    else:
        raise RuntimeError(f"Unknown engine: {engine}")
    print(json.dumps({"engine": result["engine"], "writes": len(result["writes"]), "raw": result["raw_integrity"], "recall_at_20": result.get("recall_at_20"), "no_decision_status_recall": result.get("no_decision_status_recall"), "citation_rate": result.get("citation_rate"), "first_read_after_restart": result.get("first_read_after_restart"), "warm_search_latency": result.get("warm_search_latency"), "five_candidate_batch_recall": result.get("five_candidate_batch_recall"), "unexpected_product_mention_rate": result.get("unexpected_product_mention_rate"), "failures": result["failures"]}, ensure_ascii=False))
    if result["failures"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

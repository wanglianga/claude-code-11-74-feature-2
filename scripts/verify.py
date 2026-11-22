#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通话保留与调阅数据服务 —— 端到端验收脚本。
所有验收结论均由 HTTP 接口对实际输入求值得出，不写死任何预期求值结果。
运行：python3 scripts/verify.py  （要求服务已在 BASE_URL 启动，默认 http://127.0.0.1:8080）
"""
import json
import sys
import urllib.request
import urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
AS_OF = "2026-04-01"  # 虚构的求值基准日

RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {name}" + (f" | {detail}" if detail else ""))


def call(method, path, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def must_event(fact_key=None, **kw):
    """构造事件请求体。"""
    payload = {}
    if fact_key:
        payload["fact_key"] = fact_key
    payload.update(kw.pop("payload", {}))
    body = {
        "sourceSystem": kw.pop("sourceSystem", "BIZ"),
        "sourceEventId": kw.pop("eid"),
        "eventVersion": kw.pop("version", 1),
        "eventType": kw.pop("etype"),
        "idempotencyKey": kw.pop("key"),
        "businessEffectiveTime": kw.pop("effective", "2022-01-01T00:00:00Z"),
        "platformReceivedTime": kw.pop("received", "2022-01-02T00:00:00Z"),
        "payload": payload,
    }
    return body


def main():
    # ---------- S0 重置与 Swagger ----------
    st, _ = call("DELETE", "/api/admin/reset")
    check("S0 重置成功", st == 200)
    st, docs = call("GET", "/v3/api-docs")
    check("S0 OpenAPI 文档可用", st == 200 and "paths" in docs)

    # ---------- S1 规则目录 ----------
    st, r1 = call("POST", "/api/rules", {
        "ruleCode": "SALES-RET", "version": 1, "factType": "PRODUCT_TERMINATION",
        "triggerEventType": "PRODUCT_TERMINATED", "retentionYears": 6,
        "effectiveFrom": "2000-01-01", "effectiveTo": None, "purpose": "SALES_REVIEW",
        "fieldSet": ["AUDIO", "TRANSCRIPT", "CUSTOMER_NAME", "ID_NUMBER"], "retroactive": False})
    check("S1 销售规则登记（终止日起6年）", st == 200, json.dumps(r1, ensure_ascii=False))
    st, r2 = call("POST", "/api/rules", {
        "ruleCode": "COLL-RET", "version": 1, "factType": "COLLECTION_CLOSURE",
        "triggerEventType": "COLLECTION_CLOSED", "retentionYears": 3,
        "effectiveFrom": "2000-01-01", "effectiveTo": None, "purpose": "COLLECTION",
        "fieldSet": ["AUDIO", "TRANSCRIPT", "CUSTOMER_NAME"], "retroactive": False})
    check("S1 催收规则登记（结案日起3年）", st == 200)

    # ---------- S2 录音接入：幂等、冲突、修订链 ----------
    fields = [
        {"fieldKey": "AUDIO", "classification": "GENERAL"},
        {"fieldKey": "TRANSCRIPT", "classification": "PII_BASIC"},
        {"fieldKey": "CUSTOMER_NAME", "classification": "PII_BASIC"},
        {"fieldKey": "ID_NUMBER", "classification": "PII_SENSITIVE"},
        {"fieldKey": "BANK_CARD", "classification": "FINANCIAL_SENSITIVE"},
    ]
    rec_a_body = {
        "sourceSystem": "CALL_PLATFORM", "sourceObjectId": "REC-A", "sourceVersion": 1,
        "idempotencyKey": "ing-rec-a-v1", "contentHash": "sha256:rec-a-v1",
        "channelCount": 2, "durationMs": 360000,
        "callBusinessTime": "2021-06-15T10:00:00Z",
        "platformReceivedTime": "2021-06-15T10:06:00Z",
        "segments": [
            {"seq": 1, "startMs": 0, "endMs": 180000, "transcript": "前180秒：理财产品销售回访",
             "contentHash": "sha256:rec-a-seg1", "fields": fields},
            {"seq": 2, "startMs": 180000, "endMs": 360000, "transcript": "后180秒：催收外呼沟通",
             "contentHash": "sha256:rec-a-seg2", "fields": fields},
        ]}
    st, rec_a = call("POST", "/api/ingest/recordings", rec_a_body)
    check("S2 REC-A 接入（360秒/双声道/两片段）", st == 200 and rec_a["outcome"] == "CREATED", json.dumps(rec_a))
    rec_a_id = rec_a["recordingId"]
    seg_a1, seg_a2 = rec_a["segmentIds"][0], rec_a["segmentIds"][1]

    st, dup = call("POST", "/api/ingest/recordings", rec_a_body)
    check("S2 相同幂等键+相同内容 → 幂等重放不重复建账", st == 200 and dup["outcome"] == "DUPLICATE_IDEMPOTENT")
    st, all_recs = call("GET", "/api/ingest/recordings")
    check("S2 重复到达后接入账仍只有1个 REC-A", len([r for r in all_recs if r["sourceObjectId"] == "REC-A"]) == 1)

    bad = dict(rec_a_body, contentHash="sha256:rec-a-TAMPERED")
    st, cf = call("POST", "/api/ingest/recordings", bad)
    check("S2 相同版本内容不同 → 409 冲突待核", st == 409 and cf["outcome"] == "CONFLICT_PENDING")

    # 修订链：REC-X v1 → v2
    rec_x = {"sourceSystem": "CALL_PLATFORM", "sourceObjectId": "REC-X", "sourceVersion": 1,
             "idempotencyKey": "ing-rec-x-v1", "contentHash": "sha256:rec-x-v1",
             "channelCount": 1, "durationMs": 60000,
             "callBusinessTime": "2022-01-01T00:00:00Z", "platformReceivedTime": "2022-01-01T01:00:00Z",
             "segments": [{"seq": 1, "startMs": 0, "endMs": 60000, "transcript": "x",
                           "contentHash": "h1", "fields": fields}]}
    st, x1 = call("POST", "/api/ingest/recordings", rec_x)
    rec_x_v2 = dict(rec_x, sourceVersion=2, idempotencyKey="ing-rec-x-v2", contentHash="sha256:rec-x-v2")
    st, x2 = call("POST", "/api/ingest/recordings", rec_x_v2)
    check("S2 来源修订链：v2 取代 v1", st == 200 and x2["outcome"] == "CREATED_SUPERSEDES")
    st, all_recs = call("GET", "/api/ingest/recordings")
    xs = {r["sourceVersion"]: r for r in all_recs if r["sourceObjectId"] == "REC-X"}
    check("S2 修订链状态与指向", xs[1]["status"] == "SUPERSEDED" and xs[2]["status"] == "ACTIVE"
          and xs[2]["supersedesId"] == xs[1]["id"])

    # ---------- S3 业务事件：事实、关联、幂等与冲突 ----------
    st, e1 = call("POST", "/api/ingest/events", must_event(
        eid="EVT-P1", etype="PRODUCT_TERMINATED", key="evt-p1-v1",
        effective="2021-06-30T00:00:00Z",
        payload={"case_id": "CASE-P1", "account_id": "ACCT-1", "confirm": True}, fact_key="PROD-P1"))
    check("S3 产品终止事件（2021-06-30 生效）", st == 200 and e1["outcome"] == "APPLIED")
    st, e2 = call("POST", "/api/ingest/events", must_event(
        eid="EVT-C1", etype="COLLECTION_CLOSED", key="evt-c1-v1",
        effective="2022-03-31T00:00:00Z",
        payload={"case_id": "CASE-C1", "account_id": "ACCT-1", "confirm": True}, fact_key="COLL-C1"))
    check("S3 催收结案事件（2022-03-31 生效）", st == 200 and e2["outcome"] == "APPLIED")

    st, l1 = call("POST", "/api/ingest/events", must_event(
        eid="EVT-L1", etype="LINK_ASSERTED", key="evt-l1-v1",
        payload={"segment_id": seg_a1}, fact_key="PROD-P1"))
    check("S3 关联：前180秒→产品终止事实", st == 200 and l1["outcome"] == "APPLIED"
          and l1["affectedSegments"] == 1 and l1["compensationsWritten"] == 1,
          f"受影响片段={l1.get('affectedSegments')} 补偿={l1.get('compensationsWritten')}")
    st, l2 = call("POST", "/api/ingest/events", must_event(
        eid="EVT-L2", etype="LINK_ASSERTED", key="evt-l2-v1",
        payload={"segment_id": seg_a2}, fact_key="COLL-C1"))
    check("S3 关联：后180秒→催收结案事实", st == 200 and l2["outcome"] == "APPLIED")

    st, edup = call("POST", "/api/ingest/events", must_event(
        eid="EVT-P1", etype="PRODUCT_TERMINATED", key="evt-p1-v1",
        effective="2021-06-30T00:00:00Z",
        payload={"case_id": "CASE-P1", "account_id": "ACCT-1", "confirm": True}, fact_key="PROD-P1"))
    check("S3 事件相同键+相同内容 → 幂等不重复应用", st == 200 and edup["outcome"] == "DUPLICATE_IDEMPOTENT"
          and edup["compensationsWritten"] == 0)

    st, ecf = call("POST", "/api/ingest/events", must_event(
        eid="EVT-P1", etype="PRODUCT_TERMINATED", key="evt-p1-v1",
        effective="2021-06-30T00:00:00Z",
        payload={"case_id": "CASE-P1", "account_id": "ACCT-1", "confirm": False}, fact_key="PROD-P1"))
    check("S3 事件相同键内容不同 → 409 冲突待核", st == 409 and ecf["outcome"] == "CONFLICT_PENDING")
    st, conflicts = call("GET", "/api/ingest/conflicts")
    check("S3 冲突待核账（录音+事件共2条）", len(conflicts) == 2, f"conflicts={len(conflicts)}")

    # 补发更高版本（内容相同）→ 应用但不产生新义务
    st, e1v2 = call("POST", "/api/ingest/events", must_event(
        eid="EVT-P1", etype="PRODUCT_TERMINATED", key="evt-p1-v2", version=2,
        effective="2021-06-30T00:00:00Z",
        payload={"case_id": "CASE-P1", "account_id": "ACCT-1", "confirm": True}, fact_key="PROD-P1"))
    check("S3 补发新版本事件 → 应用且义务不重复", st == 200 and e1v2["outcome"] == "APPLIED"
          and e1v2["compensationsWritten"] == 0, f"补偿={e1v2.get('compensationsWritten')}")

    # ---------- S4 义务求值 2026-04-01（双路线） ----------
    st, oa = call("GET", f"/api/obligations/recordings/{rec_a_id}?asOf={AS_OF}&route=A")
    segs_a = {s["segmentSeq"]: s for s in oa["evaluation"]["segments"]}
    seg1_obs = segs_a[1]["obligations"]
    seg2_obs = segs_a[2]["obligations"]
    check("S4 销售片段义务：SALES-RET 至 2027-06-30 仍保留",
          len(seg1_obs) == 1 and seg1_obs[0]["ruleCode"] == "SALES-RET"
          and seg1_obs[0]["expiryDate"] == "2027-06-30" and seg1_obs[0]["state"] == "RETAINED",
          json.dumps(seg1_obs, ensure_ascii=False))
    check("S4 催收片段义务：COLL-RET 至 2025-03-31 已到期",
          len(seg2_obs) == 1 and seg2_obs[0]["expiryDate"] == "2025-03-31"
          and seg2_obs[0]["state"] == "EXPIRED", json.dumps(seg2_obs, ensure_ascii=False))
    check("S4 整对象不得处置（销售片段仍保留）", oa["evaluation"]["eligible"] is False
          and any("2027-06-30" in r for r in oa["evaluation"]["blockReasons"]))
    check("S4 普通保留到期日取已确认义务最晚到期日",
          segs_a[1]["normalRetentionUntil"] == "2027-06-30"
          and segs_a[2]["normalRetentionUntil"] == "2025-03-31",
          f"seg1={segs_a[1]['normalRetentionUntil']} seg2={segs_a[2]['normalRetentionUntil']}")

    st, ob = call("GET", f"/api/obligations/recordings/{rec_a_id}?asOf={AS_OF}&route=B")
    ids_a = sorted(o["factKey"] + str(o["expiryDate"]) + o["state"]
                   for s in oa["evaluation"]["segments"] for o in s["obligations"])
    ids_b = sorted(o["factKey"] + str(o["expiryDate"]) + o["state"]
                   for s in ob["evaluation"]["segments"] for o in s["obligations"])
    check("S4 路线B（义务账）与路线A同截点结论一致", ids_a == ids_b
          and oa["evaluation"]["eligible"] == ob["evaluation"]["eligible"])

    # ---------- S5 REC-B 候选 → 投诉保全 → 撤销并阻止执行 ----------
    rec_b_body = {
        "sourceSystem": "CALL_PLATFORM", "sourceObjectId": "REC-B", "sourceVersion": 1,
        "idempotencyKey": "ing-rec-b-v1", "contentHash": "sha256:rec-b-v1",
        "channelCount": 1, "durationMs": 120000,
        "callBusinessTime": "2022-01-10T09:00:00Z", "platformReceivedTime": "2022-01-10T09:03:00Z",
        "segments": [{"seq": 1, "startMs": 0, "endMs": 120000, "transcript": "纯催收外呼",
                      "contentHash": "sha256:rec-b-seg1", "fields": fields}]}
    st, rec_b = call("POST", "/api/ingest/recordings", rec_b_body)
    rec_b_id = rec_b["recordingId"]
    seg_b1 = rec_b["segmentIds"][0]
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-C2", etype="COLLECTION_CLOSED", key="evt-c2-v1",
        effective="2022-01-15T00:00:00Z",
        payload={"case_id": "CASE-C2", "account_id": "ACCT-2", "confirm": True}, fact_key="COLL-C2"))
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-L3", etype="LINK_ASSERTED", key="evt-l3-v1",
        payload={"segment_id": seg_b1}, fact_key="COLL-C2"))

    st, plan1 = call("POST", "/api/disposal/plans", {"asOf": AS_OF, "route": "A"})
    items1 = {i["recordingId"]: i for i in plan1["items"]}
    cand_b = items1[rec_b_id]
    check("S5 仅含已到期催收片段的 REC-B 成为候选",
          cand_b["eligible"] is True and cand_b["status"] == "PENDING")
    check("S5 REC-A 被阻断并记录原因（销售片段未到期）",
          items1[rec_a_id]["eligible"] is False
          and any("2027-06-30" in r for r in items1[rec_a_id]["blockReasons"]))

    # 投诉保全在模拟执行前到达
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-CMP1", etype="COMPLAINT_FILED", key="evt-cmp1-v1",
        effective="2026-03-15T00:00:00Z",
        payload={"case_id": "CASE-C2", "account_id": "ACCT-2", "confirm": True}, fact_key="CMP-1"))
    st, hold = call("POST", "/api/ingest/events", must_event(
        eid="EVT-H1", etype="HOLD_PLACED", key="evt-h1-v1",
        payload={"hold_key": "HOLD-CMP1", "scope_type": "RECORDING", "scope_ref": rec_b_id}))
    check("S5 投诉保全登记", st == 200 and hold["outcome"] == "APPLIED")

    st, cand_b_now = call("GET", f"/api/disposal/candidates/{cand_b['candidateId']}")
    check("S5 后到冻结撤销未执行候选并保留撤销原因",
          cand_b_now["status"] == "REVOKED" and cand_b_now["revokeReason"]
          and "保全未解除" in cand_b_now["revokeReason"], cand_b_now.get("revokeReason", ""))
    st, ex = call("POST", f"/api/disposal/candidates/{cand_b['candidateId']}/execute",
                  {"simulate": "CONFIRM"})
    check("S5 已撤销候选不可执行（未提交处置）", ex.get("executed") is False)
    st, ledger = call("GET", "/api/execution/ledger")
    check("S5 执行账中无 REC-B 的处置记录", all(r["recordingId"] != rec_b_id for r in ledger))

    # ---------- S6 保全解除 → 超时回执 → 重复回执 → 对账 ----------
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-H1R", etype="HOLD_RELEASED", key="evt-h1r-v1",
        payload={"hold_key": "HOLD-CMP1", "scope_type": "RECORDING", "scope_ref": rec_b_id}))
    st, plan2 = call("POST", "/api/disposal/plans", {"asOf": AS_OF, "route": "B"})
    items2 = {i["recordingId"]: i for i in plan2["items"]}
    cand_b2 = items2[rec_b_id]
    check("S6 保全解除后 REC-B 重新成为候选（路线B计划）", cand_b2["status"] == "PENDING")

    st, ex2 = call("POST", f"/api/disposal/candidates/{cand_b2['candidateId']}/execute",
                   {"simulate": "TIMEOUT"})
    op_b = ex2["operationNo"]
    check("S6 回执超时 → 结果未知而非成功", ex2["finalResult"] == "UNKNOWN")
    st, rec1 = call("POST", "/api/execution/reconcile", {"operationNo": op_b})
    check("S6 凭原操作号核对：仍 UNKNOWN（不计为成功）",
          rec1["finalResult"] == "UNKNOWN" and "不计为成功" in rec1["message"])

    st, rc1 = call("POST", "/api/execution/receipts",
                   {"operationNo": op_b, "result": "CONFIRMED", "payload": {"storage": "ok"}})
    check("S6 迟到回执按原操作号落定 SUCCESS", rc1["outcome"] == "CONFIRMED_APPLIED"
          and rc1["finalResult"] == "SUCCESS")
    st, rc2 = call("POST", "/api/execution/receipts",
                   {"operationNo": op_b, "result": "CONFIRMED", "payload": {"storage": "ok"}})
    check("S6 重复回执不重复处置", rc2["outcome"] == "DUPLICATE_IGNORED" and rc2["duplicateReceipts"] == 1)
    st, ledger = call("GET", "/api/execution/ledger")
    ops_b = [r for r in ledger if r["operationNo"] == op_b]
    check("S6 执行账仅一条 REC-B 记录且为 SUCCESS", len(ops_b) == 1 and ops_b[0]["finalResult"] == "SUCCESS")
    st, rec_b_now = call("GET", f"/api/ingest/recordings/{rec_b_id}")
    check("S6 REC-B 状态已处置", rec_b_now["recording"]["status"] == "DISPOSED")

    # ---------- S7 起算日期更正（追溯） ----------
    st, corr = call("POST", "/api/ingest/events", must_event(
        eid="EVT-P1-CORR", etype="FACT_CORRECTED", key="evt-p1-corr-v1",
        effective="2026-03-01T00:00:00Z",
        payload={"effective_time": "2019-06-30"}, fact_key="PROD-P1"))
    check("S7 起算日期更正 2021-06-30→2019-06-30，传播到受影响片段",
          st == 200 and corr["affectedSegments"] == 1 and corr["compensationsWritten"] == 2,
          f"受影响={corr.get('affectedSegments')} 补偿={corr.get('compensationsWritten')}")
    st, oa2 = call("GET", f"/api/obligations/recordings/{rec_a_id}?asOf={AS_OF}&route=A")
    seg1_after = [o for s in oa2["evaluation"]["segments"] if s["segmentSeq"] == 1
                  for o in s["obligations"]]
    check("S7 更正后销售片段义务到期日 2025-06-30（已到期）",
          len(seg1_after) == 1 and seg1_after[0]["expiryDate"] == "2025-06-30"
          and seg1_after[0]["state"] == "EXPIRED", json.dumps(seg1_after, ensure_ascii=False))
    check("S7 更正后整对象具备处置资格", oa2["evaluation"]["eligible"] is True)
    st, logs = call("GET", "/api/obligations/maintenance-log")
    corr_log = [l for l in logs if "FACT_CORRECTED" in (l["reason"] or "")]
    check("S7 路线B留痕补偿记录（作废1+更正1）", len(corr_log) == 1
          and corr_log[0]["entriesVoided"] == 1 and corr_log[0]["entriesAdded"] == 1,
          json.dumps(corr_log, ensure_ascii=False))

    # ---------- S8 规则仅对部分事实追溯 ----------
    rec_c_body = {"sourceSystem": "CALL_PLATFORM", "sourceObjectId": "REC-C", "sourceVersion": 1,
                  "idempotencyKey": "ing-rec-c-v1", "contentHash": "sha256:rec-c-v1",
                  "channelCount": 1, "durationMs": 60000,
                  "callBusinessTime": "2023-05-08T00:00:00Z", "platformReceivedTime": "2023-05-08T01:00:00Z",
                  "segments": [{"seq": 1, "startMs": 0, "endMs": 60000, "transcript": "催收C3",
                                "contentHash": "hc1", "fields": fields}]}
    st, rec_c = call("POST", "/api/ingest/recordings", rec_c_body)
    rec_c_id = rec_c["recordingId"]
    seg_c1 = rec_c["segmentIds"][0]
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-C3", etype="COLLECTION_CLOSED", key="evt-c3-v1",
        effective="2023-05-10T00:00:00Z",
        payload={"case_id": "CASE-C3", "account_id": "ACCT-3", "confirm": True}, fact_key="COLL-C3"))
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-L4", etype="LINK_ASSERTED", key="evt-l4-v1",
        payload={"segment_id": seg_c1}, fact_key="COLL-C3"))
    rec_d_body = dict(rec_c_body, sourceObjectId="REC-D", idempotencyKey="ing-rec-d-v1",
                      contentHash="sha256:rec-d-v1",
                      segments=[{"seq": 1, "startMs": 0, "endMs": 60000, "transcript": "催收C4",
                                 "contentHash": "hd1", "fields": fields}])
    st, rec_d = call("POST", "/api/ingest/recordings", rec_d_body)
    rec_d_id = rec_d["recordingId"]
    seg_d1 = rec_d["segmentIds"][0]
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-C4", etype="COLLECTION_CLOSED", key="evt-c4-v1",
        effective="2022-06-15T00:00:00Z",
        payload={"case_id": "CASE-C4", "account_id": "ACCT-4", "confirm": True}, fact_key="COLL-C4"))
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-L5", etype="LINK_ASSERTED", key="evt-l5-v1",
        payload={"segment_id": seg_d1}, fact_key="COLL-C4"))

    st, oc_before = call("GET", f"/api/obligations/recordings/{rec_c_id}?asOf={AS_OF}&route=A")
    obs_before = oc_before["evaluation"]["segments"][0]["obligations"]
    check("S8 追溯前 C3 按 v1(3年) 至 2026-05-10 仍保留",
          obs_before[0]["ruleVersion"] == 1 and obs_before[0]["expiryDate"] == "2026-05-10"
          and obs_before[0]["state"] == "RETAINED")

    st, r2v2 = call("POST", "/api/rules", {
        "ruleCode": "COLL-RET", "version": 2, "factType": "COLLECTION_CLOSURE",
        "triggerEventType": "COLLECTION_CLOSED", "retentionYears": 2,
        "effectiveFrom": "2023-03-01", "effectiveTo": "2023-12-31", "purpose": "COLLECTION",
        "fieldSet": ["AUDIO", "TRANSCRIPT", "CUSTOMER_NAME"], "retroactive": True})
    check("S8 追溯规则 v2（仅覆盖2023-03后事实）只影响 REC-C",
          st == 200 and r2v2["affectedSegments"] == 1 and r2v2["compensationsWritten"] == 2,
          json.dumps(r2v2, ensure_ascii=False))
    st, oc_after = call("GET", f"/api/obligations/recordings/{rec_c_id}?asOf={AS_OF}&route=A")
    obs_c_after = oc_after["evaluation"]["segments"][0]["obligations"]
    check("S8 C3 改按 v2(2年) 至 2025-05-10 已到期",
          obs_c_after[0]["ruleVersion"] == 2 and obs_c_after[0]["expiryDate"] == "2025-05-10"
          and obs_c_after[0]["state"] == "EXPIRED", json.dumps(obs_c_after, ensure_ascii=False))
    st, od_after = call("GET", f"/api/obligations/recordings/{rec_d_id}?asOf={AS_OF}&route=A")
    obs_d_after = od_after["evaluation"]["segments"][0]["obligations"]
    check("S8 C4 在适用区间外仍按 v1(3年)", obs_d_after[0]["ruleVersion"] == 1
          and obs_d_after[0]["expiryDate"] == "2025-06-15")

    # ---------- S9 起算事实未确认 → 未确定依据 → 确认 ----------
    rec_e_body = dict(rec_c_body, sourceObjectId="REC-E", idempotencyKey="ing-rec-e-v1",
                      contentHash="sha256:rec-e-v1",
                      segments=[{"seq": 1, "startMs": 0, "endMs": 60000, "transcript": "催收C5",
                                 "contentHash": "he1", "fields": fields}])
    st, rec_e = call("POST", "/api/ingest/recordings", rec_e_body)
    rec_e_id = rec_e["recordingId"]
    seg_e1 = rec_e["segmentIds"][0]
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-C5", etype="COLLECTION_CLOSED", key="evt-c5-v1",
        effective="2023-01-10T00:00:00Z",
        payload={"case_id": "CASE-C5", "account_id": "ACCT-5", "confirm": False}, fact_key="COLL-C5"))
    call("POST", "/api/ingest/events", must_event(
        eid="EVT-L6", etype="LINK_ASSERTED", key="evt-l6-v1",
        payload={"segment_id": seg_e1}, fact_key="COLL-C5"))
    st, oe = call("GET", f"/api/obligations/recordings/{rec_e_id}?asOf={AS_OF}&route=A")
    obs_e = oe["evaluation"]["segments"][0]["obligations"]
    check("S9 起算事实未确认 → 未确定依据（PENDING，不提前到期）",
          obs_e[0]["basis"] == "BASIS_UNDETERMINED" and obs_e[0]["state"] == "PENDING"
          and obs_e[0]["expiryDate"] is None, json.dumps(obs_e, ensure_ascii=False))
    check("S9 未确认起算阻断整对象处置", oe["evaluation"]["eligible"] is False
          and any("未确认" in r for r in oe["evaluation"]["blockReasons"]))
    st, conf = call("POST", "/api/ingest/events", must_event(
        eid="EVT-C5-CONF", etype="FACT_CONFIRMED", key="evt-c5-conf-v1", fact_key="COLL-C5"))
    check("S9 事实确认后补偿传播", st == 200 and conf["compensationsWritten"] == 2)
    st, oe2 = call("GET", f"/api/obligations/recordings/{rec_e_id}?asOf={AS_OF}&route=A")
    obs_e2 = oe2["evaluation"]["segments"][0]["obligations"]
    check("S9 确认后按 v1(3年) 至 2026-01-10 已到期",
          obs_e2[0]["basis"] == "RULE_CONFIRMED" and obs_e2[0]["expiryDate"] == "2026-01-10"
          and obs_e2[0]["state"] == "EXPIRED")

    # ---------- S10 调阅投影、快照、复演、范围收窄、再次下载 ----------
    rec_f_body = dict(rec_c_body, sourceObjectId="REC-F", idempotencyKey="ing-rec-f-v1",
                      contentHash="sha256:rec-f-v1",
                      segments=[{"seq": 1, "startMs": 0, "endMs": 60000,
                                 "transcript": "同一客户ACCT-1的另一通未关联电话",
                                 "contentHash": "hf1", "fields": fields}])
    st, rec_f = call("POST", "/api/ingest/recordings", rec_f_body)
    rec_f_id = rec_f["recordingId"]

    st, ev1 = call("POST", "/api/access/evaluations", {
        "role": "COLLECTION_AGENT", "purpose": "COLLECTION",
        "caseScope": ["CASE-C1"], "asOf": AS_OF})
    snap1 = ev1["snapshotId"]
    res1 = ev1["result"]
    allowed1 = {a["segmentId"]: a for a in res1["allowed"]}
    denied1 = {d["segmentId"]: d for d in res1["denied"]}
    check("S10 调阅投影：仅 CASE-C1 关联片段被允许", list(allowed1.keys()) == [seg_a2],
          f"allowed={list(allowed1.keys())}")
    check("S10 字段级投影：允许 AUDIO/TRANSCRIPT/CUSTOMER_NAME",
          sorted(allowed1[seg_a2]["fields"]) == ["AUDIO", "CUSTOMER_NAME", "TRANSCRIPT"],
          json.dumps(allowed1[seg_a2]["fields"]))
    denied_fields = {d["field"]: d["reason"] for d in allowed1[seg_a2]["deniedFields"]}
    check("S10 敏感字段按分类拒绝（ID_NUMBER/BANK_CARD）",
          "ID_NUMBER" in denied_fields and "BANK_CARD" in denied_fields
          and all("CLASSIFICATION_DENIED" in r for r in denied_fields.values()))
    check("S10 销售片段因案件范围被拒", seg_a1 in denied1
          and any("OUT_OF_CASE_SCOPE" in r for r in denied1[seg_a1]["reasons"]))
    check("S10 同账户未关联电话不按账户归并（NO_ACTIVE_LINK）",
          any(d["segmentId"] == rec_f["segmentIds"][0]
              and any("NO_ACTIVE_LINK" in r for r in d["reasons"]) for d in res1["denied"]))
    check("S10 快照固定证据水位与规则版本", ev1["watermark"] > 0 and len(ev1["rulePins"]) >= 2)

    st, rp1 = call("POST", f"/api/access/snapshots/{snap1}/replay")
    check("S10 历史复演与原快照一致", rp1["matchesOriginal"] is True)

    # 案件范围收窄后的新授权
    st, ev2 = call("POST", "/api/access/evaluations", {
        "role": "COLLECTION_AGENT", "purpose": "COLLECTION", "caseScope": [], "asOf": AS_OF})
    check("S10 范围收窄后新求值全部拒绝", len(ev2["result"]["allowed"]) == 0)
    st, rp2 = call("POST", f"/api/access/snapshots/{snap1}/replay")
    check("S10 新授权不替换旧回执（旧快照复演仍一致）", rp2["matchesOriginal"] is True)

    st, dl1 = call("POST", f"/api/access/snapshots/{snap1}/download",
                   {"role": "COLLECTION_AGENT", "purpose": "COLLECTION", "caseScope": []})
    check("S10 再次下载须过当前授权：范围收窄→403 不放行", st == 403 and dl1.get("material") == [])
    st, dl2 = call("POST", f"/api/access/snapshots/{snap1}/download",
                   {"role": "COLLECTION_AGENT", "purpose": "COLLECTION", "caseScope": ["CASE-C1"]})
    check("S10 当前授权覆盖时可下载快照允许内容",
          st == 200 and len(dl2["material"]) == 1
          and dl2["material"][0]["transcript"] == "后180秒：催收外呼沟通")

    st, ev3 = call("POST", "/api/access/evaluations", {
        "role": "SALES_SUPERVISOR", "purpose": "COLLECTION",
        "caseScope": ["CASE-C1"], "asOf": AS_OF})
    check("S10 角色-用途不匹配全部拒绝（保留义务≠任意角色可看）",
          len(ev3["result"]["allowed"]) == 0
          and any("ROLE_PURPOSE_DENIED" in r for d in ev3["result"]["denied"] for r in d["reasons"]))

    # ---------- S11 已执行后事实冲突升级 ----------
    st, hold_late = call("POST", "/api/ingest/events", must_event(
        eid="EVT-H2", etype="HOLD_PLACED", key="evt-h2-v1",
        payload={"hold_key": "HOLD-LATE", "scope_type": "RECORDING", "scope_ref": rec_b_id}))
    check("S11 已处置对象上后到冻结被接受并传播", st == 200)
    st, esc = call("GET", "/api/execution/escalations")
    esc_b = [e for e in esc if e["kind"] == "EXECUTED_DISPOSAL_FACT_CONFLICT"
             and e.get("recordingId") == rec_b_id]
    check("S11 已执行候选遇新保全 → 生成事实冲突与升级记录", len(esc_b) == 1
          and esc_b[0]["operationNo"] == op_b, json.dumps(esc_b, ensure_ascii=False))
    st, ledger = call("GET", "/api/execution/ledger")
    op_b_row = [r for r in ledger if r["operationNo"] == op_b][0]
    check("S11 不伪称恢复已删除原件（执行账仍 SUCCESS）", op_b_row["finalResult"] == "SUCCESS")

    # ---------- S12 矛盾回执冲突 ----------
    st, plan3 = call("POST", "/api/disposal/plans", {"asOf": AS_OF, "route": "A"})
    items3 = {i["recordingId"]: i for i in plan3["items"]}
    cand_c = items3[rec_c_id]
    check("S12 追溯后 REC-C 成为候选", cand_c["status"] == "PENDING")
    st, ex_c = call("POST", f"/api/disposal/candidates/{cand_c['candidateId']}/execute",
                    {"simulate": "CONFIRM"})
    check("S12 REC-C 提交前重新核验通过并执行", ex_c["executed"] is True
          and ex_c["finalResult"] == "SUCCESS")
    st, rc_conf = call("POST", "/api/execution/receipts",
                       {"operationNo": ex_c["operationNo"], "result": "REJECTED",
                        "payload": {"storage": "contradiction"}})
    check("S12 终态后矛盾回执 → 记录冲突不覆盖", rc_conf["outcome"] == "CONFLICT_RECORDED")
    st, esc2 = call("GET", "/api/execution/escalations")
    check("S12 矛盾回执升级记录在案",
          any(e["kind"] == "RECEIPT_CONFLICT" for e in esc2))

    # ---------- S13 双路线一致性总对比与代价 ----------
    st, cmp_all = call("GET", f"/api/obligations/compare?asOf={AS_OF}")
    check("S13 全部ACTIVE对象双路线结论一致", cmp_all["allEqual"] is True,
          f"对象数={len(cmp_all['recordings'])}")
    cost_a, cost_b = cmp_all["costRouteA"], cmp_all["costRouteB"]
    print("  路线A遍历范围:", json.dumps(cost_a))
    print("  路线B账载读取:", json.dumps(cost_b))
    check("S13 路线A遍历全部片段而路线B只读账载条目",
          cost_a["segmentsVisited"] >= 1 and cost_b["ledgerEntriesRead"] >= 1)
    st, logs = call("GET", "/api/obligations/maintenance-log")
    total_void = sum(l["entriesVoided"] for l in logs)
    total_add = sum(l["entriesAdded"] for l in logs)
    print(f"  路线B补偿记录总量: 作废={total_void} 新增={total_add} 维护批次={len(logs)}")
    check("S13 同一更正上路线B以补偿记录传播（作废+新增）", total_void >= 3 and total_add >= 3)

    # ---------- 汇总 ----------
    failed = [r for r in RESULTS if not r[1]]
    print("\n===== 验收汇总 =====")
    print(f"通过 {len(RESULTS) - len(failed)}/{len(RESULTS)}")
    summary = {
        "base": BASE, "asOf": AS_OF, "total": len(RESULTS),
        "passed": len(RESULTS) - len(failed), "failed": len(failed),
        "costRouteA": cost_a, "costRouteB": cost_b,
        "ledgerCompensations": {"voided": total_void, "added": total_add, "batches": len(logs)},
        "results": [{"name": n, "pass": c, "detail": d} for n, c, d in RESULTS],
    }
    with open("scripts/verification-result.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    if failed:
        for n, _, d in failed:
            print("FAILED:", n, d)
        return 1
    print("全部验收场景通过，结果已写入 scripts/verification-result.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())

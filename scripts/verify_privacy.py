#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
证据视图/隐私遮蔽域 —— 端到端验收脚本。
覆盖：实体定位器（跨两段+识别修正）、遮蔽规则版本库（生成中修订）、证据视图生成器（按需/物化双路线）、
范围校验器（已解封案件随后收窄）、临时还原审批台；并核验遮蔽完整性、版本绑定与新下载拦截。
所有结论均由 HTTP 接口对实际输入求值得出。
运行：python3 scripts/verify_privacy.py [BASE_URL]
"""
import json
import sys
import urllib.request
import urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f" | {detail}" if detail else ""))


def call(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
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


def event(eid, etype, key, payload, effective="2024-01-01T00:00:00Z"):
    return {"sourceSystem": "BIZ", "sourceEventId": eid, "eventVersion": 1, "eventType": etype,
            "idempotencyKey": key, "businessEffectiveTime": effective,
            "platformReceivedTime": "2024-01-02T00:00:00Z", "payload": payload}


def masks_of(view):
    out = []
    for seg in view["maskedContent"]:
        out.extend(seg["masks"])
    return out


def main():
    # ---------- P0 重置 ----------
    st, _ = call("DELETE", "/api/admin/reset")
    check("P0 重置成功", st == 200)

    # ---------- P1 遮蔽规则版本库 v1 ----------
    rule_v1 = {
        "ruleCode": "DEFAULT", "version": 1, "changeNote": "初版：证号/住址/健康全遮蔽，卡号留尾4位",
        "typeConfig": {
            "ID_CARD": {"action": "FULL_MASK", "placeholder": "【身份证号】"},
            "BANK_CARD": {"action": "KEEP_LAST", "placeholder": "*", "keep": 4},
            "ADDRESS": {"action": "FULL_MASK", "placeholder": "【住址】"},
            "HEALTH": {"action": "FULL_MASK", "placeholder": "【健康情况】"}}}
    st, r1 = call("POST", "/api/privacy/masking-rules", rule_v1)
    check("P1 遮蔽规则 DEFAULT v1 登记", st == 200 and r1["version"] == 1 and r1["configHash"].startswith("sha256:"))
    v1_hash = r1["configHash"]
    st, dup = call("POST", "/api/privacy/masking-rules", rule_v1)
    check("P1 同版本重复登记 → 409", st == 409)

    # ---------- P2 接入含跨段实体的投诉录音 ----------
    # 银行卡 6222021234567890 被切在两段边界；身份证完整落段1；住址/健康落段2
    seg1 = "客户陈述身份证号110101199003078888，确认银行卡62220212"
    seg2 = "34567890办理扣款，住址北京市朝阳区建国路88号，诊断高血压二级"
    body = {
        "sourceSystem": "CALL_PLATFORM", "sourceObjectId": "REC-P", "sourceVersion": 1,
        "idempotencyKey": "ing-rec-p-v1", "contentHash": "sha256:rec-p-v1",
        "channelCount": 2, "durationMs": 120000,
        "callBusinessTime": "2024-02-01T10:00:00Z", "platformReceivedTime": "2024-02-01T10:05:00Z",
        "segments": [
            {"seq": 1, "startMs": 0, "endMs": 60000, "transcript": seg1, "contentHash": "sha256:p-s1"},
            {"seq": 2, "startMs": 60000, "endMs": 120000, "transcript": seg2, "contentHash": "sha256:p-s2"}]}
    st, rec = call("POST", "/api/ingest/recordings", body)
    check("P2 投诉录音接入（两段）", st == 200 and rec["outcome"] == "CREATED")
    rec_id = rec["recordingId"]
    s1, s2 = rec["segmentIds"]

    # 投诉事实 + 两段显式关联
    call("POST", "/api/ingest/events", event("EVT-CMP", "COMPLAINT_FILED", "evt-cmp",
         {"fact_key": "CMP-QA", "case_id": "CASE-QA", "account_id": "ACCT-Q", "confirm": True}))
    call("POST", "/api/ingest/events", event("EVT-LK1", "LINK_ASSERTED", "evt-lk1",
         {"segment_id": s1, "fact_key": "CMP-QA"}))
    call("POST", "/api/ingest/events", event("EVT-LK2", "LINK_ASSERTED", "evt-lk2",
         {"segment_id": s2, "fact_key": "CMP-QA"}))

    # ---------- P3 实体定位器：跨段缝合 + 四类实体 ----------
    st, loc = call("POST", f"/api/privacy/entities/recordings/{rec_id}/locate")
    check("P3 自动定位登记4个敏感实体", st == 200 and loc["registeredCount"] == 4, json.dumps(loc, ensure_ascii=False))
    st, ents = call("GET", f"/api/privacy/entities/recordings/{rec_id}")
    by_type = {e["entityType"]: e for e in ents}
    card = by_type["BANK_CARD"]
    check("P3 银行卡跨两段：PREFIX(段1)+SUFFIX(段2) 两条提及",
          len(card["mentions"]) == 2
          and {m["matchMode"] for m in card["mentions"]} == {"PREFIX", "SUFFIX"}
          and {m["segmentSeq"] for m in card["mentions"]} == {1, 2},
          json.dumps(card["mentions"], ensure_ascii=False))
    joined = "".join(m["coveredText"] for m in sorted(card["mentions"], key=lambda m: m["mentionSeq"]))
    check("P3 跨段提及拼回完整卡号 6222021234567890", joined == "6222021234567890", joined)
    idc = by_type["ID_CARD"]
    check("P3 身份证完整落段1且偏移回链正确",
          len(idc["mentions"]) == 1 and idc["mentions"][0]["matchMode"] == "FULL"
          and idc["mentions"][0]["segmentSeq"] == 1
          and seg1[idc["mentions"][0]["startOffset"]:idc["mentions"][0]["endOffset"]] == "110101199003078888")
    check("P3 住址与健康情况均定位到段2",
          by_type["ADDRESS"]["mentions"][0]["segmentSeq"] == 2
          and by_type["HEALTH"]["mentions"][0]["segmentSeq"] == 2)
    # 幂等：再次定位不重复登记
    st, loc2 = call("POST", f"/api/privacy/entities/recordings/{rec_id}/locate")
    check("P3 重复定位幂等（registeredCount=0）", loc2["registeredCount"] == 0)

    # ---------- P4 物化视图（FRESH）+ 遮蔽完整性 + 版本绑定 ----------
    view_req = {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                "caseScope": ["CASE-QA"], "recordingId": rec_id, "revealApprovalId": None}
    st, mv1 = call("POST", "/api/privacy/views/materialized", view_req)
    check("P4 物化视图生成 FRESH", st == 200 and mv1["status"] == "FRESH" and mv1["route"] == "MATERIALIZED")
    check("P4 视图绑定 DEFAULT v1（configHash 回链）",
          any(p["ruleCode"] == "DEFAULT" and p["version"] == 1 and p["configHash"] == v1_hash
              for p in mv1["rulePins"]))
    check("P4 视图绑定身份证实体 rev1",
          any(p["entityType"] == "ID_CARD" and p["revision"] == 1 for p in mv1["entityPins"]))
    masked_text = "".join(seg["maskedTranscript"] for seg in mv1["maskedContent"])
    check("P4 遮蔽完整性：身份证/卡号/住址/健康原文均不出现",
          "110101199003078888" not in masked_text and "6222021234567890" not in masked_text
          and "北京市朝阳区建国路88号" not in masked_text and "高血压二级" not in masked_text,
          masked_text)
    check("P4 卡号 KEEP_LAST 留尾4位、前缀全遮蔽",
          "****0212" in masked_text and "****7890" in masked_text and "62220212" not in masked_text)
    check("P4 每处遮蔽回链字段位置与规则版本",
          all(m.get("segmentId") and "startOffset" in m and m.get("rule", {}).get("version") == 1
              for m in masks_of(mv1)))
    check("P4 物化重复调阅直接复用（同 viewId）",
          call("POST", "/api/privacy/views/materialized", view_req)[1]["viewId"] == mv1["viewId"])
    st, dl = call("POST", f"/api/privacy/views/{mv1['viewId']}/download",
                  {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW", "caseScope": ["CASE-QA"]})
    check("P4 FRESH 视图下载放行", st == 200 and dl["downloadCount"] == 1)

    # ---------- P5 质检员最小权限：只见投诉关联片段 ----------
    st, qv = call("POST", "/api/privacy/views/on-demand",
                  {"role": "QUALITY_INSPECTOR", "purpose": "COMPLAINT_REVIEW",
                   "caseScope": ["CASE-QA"], "recordingId": rec_id, "revealApprovalId": None})
    check("P5 质检员按需视图仅含 CASE-QA 关联的两段",
          st == 200 and {s["segmentSeq"] for s in qv["maskedContent"]} == {1, 2}
          and all("【身份证号】" in s["maskedTranscript"] or "【住址】" in s["maskedTranscript"]
                  or "*" in s["maskedTranscript"] for s in qv["maskedContent"]))
    st, denied = call("POST", "/api/privacy/views/on-demand",
                      {"role": "QUALITY_INSPECTOR", "purpose": "COMPLAINT_REVIEW",
                       "caseScope": ["CASE-OTHER"], "recordingId": rec_id, "revealApprovalId": None})
    check("P5 案件范围外请求拒绝出视图（最小授权）", st == 403)

    # ---------- P6 识别修正 → 物化视图准确失效 → 新下载拦截 → 重物化 ----------
    st, corr = call("POST", f"/api/privacy/entities/recordings/{rec_id}/corrections",
                    {"entityType": "ID_CARD", "previousEntityId": idc["entityId"],
                     "correctedCanonicalValue": "11010119900307889X", "mentions": None})
    check("P6 身份证识别修正登记 rev2 并取代 rev1",
          st == 200 and corr["revision"] == 2 and corr["supersedesId"] == idc["entityId"],
          json.dumps(corr, ensure_ascii=False))
    st, stale_view = call("GET", f"/api/privacy/views/{mv1['viewId']}")
    check("P6 修正事件使物化视图转 STALE（ENTITY_REVISED）",
          stale_view["status"] == "STALE" and "ENTITY_REVISED" in stale_view["invalidateReason"],
          stale_view.get("invalidateReason", ""))
    st, blocked = call("POST", f"/api/privacy/views/{mv1['viewId']}/download",
                       {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW", "caseScope": ["CASE-QA"]})
    check("P6 STALE 视图新下载被拦截 403", st == 403 and "ENTITY_REVISED" in blocked["message"])
    st, ledger = call("GET", f"/api/privacy/views/{mv1['viewId']}/downloads")
    check("P6 拦截在下载留痕中记 granted=false",
          any(d["granted"] is False and "ENTITY_REVISED" in d["reason"] for d in ledger))
    st, mv2 = call("POST", "/api/privacy/views/materialized", view_req)
    check("P6 重新物化恢复 FRESH 且绑定实体 rev2",
          st == 200 and mv2["status"] == "FRESH"
          and any(p["entityType"] == "ID_CARD" and p["revision"] == 2 for p in mv2["entityPins"]))
    masked2 = "".join(s["maskedTranscript"] for s in mv2["maskedContent"])
    check("P6 重物化后遮蔽仍完整（新旧身份证明文均不出现）",
          "110101199003078888" not in masked2 and "11010119900307889X" not in masked2)
    st, _ = call("POST", f"/api/privacy/views/{mv2['viewId']}/download",
                 {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW", "caseScope": ["CASE-QA"]})
    check("P6 FRESH 重物化视图下载放行", st == 200)

    # ---------- P7 规则在生成中修订：v1/v2 版本绑定与失效 ----------
    st, od_before = call("POST", "/api/privacy/views/on-demand", view_req)
    check("P7 修订前按需视图绑定 DEFAULT v1",
          st == 200 and any(p["version"] == 1 for p in od_before["rulePins"]))
    rule_v2 = {
        "ruleCode": "DEFAULT", "version": 2, "changeNote": "修订：银行卡改全遮蔽",
        "typeConfig": {
            "ID_CARD": {"action": "FULL_MASK", "placeholder": "【身份证号】"},
            "BANK_CARD": {"action": "FULL_MASK", "placeholder": "【银行卡号】"},
            "ADDRESS": {"action": "FULL_MASK", "placeholder": "【住址】"},
            "HEALTH": {"action": "FULL_MASK", "placeholder": "【健康情况】"}}}
    st, r2 = call("POST", "/api/privacy/masking-rules", rule_v2)
    check("P7 DEFAULT v2 登记且 v1 保留可回链", st == 200 and r2["version"] == 2 and r2["configHash"] != v1_hash)
    st, mv2_now = call("GET", f"/api/privacy/views/{mv2['viewId']}")
    check("P7 规则修订使绑定 v1 的物化视图转 STALE（MASKING_RULE_REVISED）",
          mv2_now["status"] == "STALE" and "MASKING_RULE_REVISED" in mv2_now["invalidateReason"])
    st, blocked2 = call("POST", f"/api/privacy/views/{mv2['viewId']}/download",
                        {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW", "caseScope": ["CASE-QA"]})
    check("P7 规则过时视图新下载拦截 403", st == 403 and "MASKING_RULE_REVISED" in blocked2["message"])
    st, od_after = call("POST", "/api/privacy/views/on-demand", view_req)
    check("P7 修订后按需视图绑定 DEFAULT v2",
          st == 200 and any(p["version"] == 2 for p in od_after["rulePins"]))
    masked_after = "".join(s["maskedTranscript"] for s in od_after["maskedContent"])
    check("P7 v2 下银行卡整段全遮蔽（不再留尾号）",
          "【银行卡号】" in masked_after and "****7890" not in masked_after)
    check("P7 修订前视图仍留存 v1 回链（历史不被改写）",
          any(p["version"] == 1 for p in od_before["rulePins"]))

    # ---------- P8 已解封案件随后收窄：范围校验与下载拦截 ----------
    call("POST", "/api/ingest/events", event("EVT-HOLD", "HOLD_PLACED", "evt-hold",
         {"hold_key": "HOLD-QA", "scope_type": "RECORDING", "scope_ref": rec_id}))
    call("POST", "/api/ingest/events", event("EVT-HOLD-R", "HOLD_RELEASED", "evt-hold-r",
         {"hold_key": "HOLD-QA", "scope_type": "RECORDING", "scope_ref": rec_id}))
    st, mv3 = call("POST", "/api/privacy/views/materialized", view_req)
    check("P8 保全解除（已解封）后仍可物化 FRESH 视图", st == 200 and mv3["status"] == "FRESH")
    st, narrow = call("POST", f"/api/privacy/views/{mv3['viewId']}/download",
                      {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW", "caseScope": []})
    check("P8 案件范围收窄后新下载拦截 403（CASE_SCOPE_NARROWED）",
          st == 403 and "CASE_SCOPE_NARROWED" in narrow["message"])
    st, narrow_other = call("POST", f"/api/privacy/views/{mv3['viewId']}/download",
                            {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW", "caseScope": ["CASE-OTHER"]})
    check("P8 换成不含原案件的范围同样拦截", st == 403 and "CASE_SCOPE_NARROWED" in narrow_other["message"])
    st, ok = call("POST", f"/api/privacy/views/{mv3['viewId']}/download",
                  {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW", "caseScope": ["CASE-QA"]})
    check("P8 范围仍覆盖时下载放行", st == 200)

    # ---------- P9 临时还原审批台 ----------
    idc_rev2 = next(e for e in call("GET", f"/api/privacy/entities/recordings/{rec_id}")[1]
                    if e["entityType"] == "ID_CARD" and e["status"] == "ACTIVE")
    st, bad_apply = call("POST", "/api/privacy/reveal-approvals",
                         {"requesterRole": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                          "caseScope": ["CASE-QA"], "recordingId": rec_id, "entityIds": [],
                          "reason": "x", "ttlMinutes": 30})
    check("P9 无实体集合的还原申请拒绝（不接受整录音通配）", st == 400)
    st, apply_resp = call("POST", "/api/privacy/reveal-approvals",
                          {"requesterRole": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                           "caseScope": ["CASE-QA"], "recordingId": rec_id,
                           "entityIds": [idc_rev2["entityId"]],
                           "reason": "合规核验完整证据", "ttlMinutes": 30})
    check("P9 还原申请受理 PENDING", st == 200 and apply_resp["status"] == "PENDING")
    approval_no = apply_resp["approvalNo"]
    approval_id = apply_resp["id"]
    st, pending_use = call("POST", "/api/privacy/views/on-demand",
                           {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                            "caseScope": ["CASE-QA"], "recordingId": rec_id,
                            "revealApprovalId": approval_id})
    check("P9 未批准前凭审批生成视图被拒", st == 403)
    st, decision = call("POST", f"/api/privacy/reveal-approvals/{approval_no}/decision",
                        {"approve": True, "approver": "DPO-LEE", "note": "同意限时还原身份证"})
    check("P9 审批批准 APPROVED", st == 200 and decision["status"] == "APPROVED")
    st, rv = call("POST", "/api/privacy/views/on-demand",
                  {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                   "caseScope": ["CASE-QA"], "recordingId": rec_id,
                   "revealApprovalId": approval_id})
    check("P9 批准后生成带还原视图（200）", st == 200 and rv["revealApprovalId"] == approval_id)
    id_mask = next(m for m in masks_of(rv) if m["entityType"] == "ID_CARD")
    check("P9 授权实体临时还原为转写原文且回链审批单",
          id_mask["revealed"] is True and id_mask["maskedText"] == "110101199003078888"
          and id_mask["approvalNo"] == approval_no and id_mask["rule"] is None)
    check("P9 未授权实体仍遮蔽（卡号/住址/健康）",
          all(m["revealed"] is False for m in masks_of(rv) if m["entityType"] != "ID_CARD"))
    st, revoke = call("POST", f"/api/privacy/reveal-approvals/{approval_no}/revoke", {"note": "提前收回"})
    check("P9 审批撤销 REVOKED", st == 200 and revoke["status"] == "REVOKED")
    st, revoked_use = call("POST", "/api/privacy/views/on-demand",
                           {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                            "caseScope": ["CASE-QA"], "recordingId": rec_id,
                            "revealApprovalId": approval_id})
    check("P9 撤销后凭审批生成视图被拒", st == 403 and "REVOKED" in revoked_use["message"])

    # ---------- P10 转写更正：唯一原证据不删除 + 物化失效 + 旧版拒出新视图 ----------
    body_v2 = dict(body, sourceVersion=2, idempotencyKey="ing-rec-p-v2",
                   contentHash="sha256:rec-p-v2")
    st, rec_v2 = call("POST", "/api/ingest/recordings", body_v2)
    check("P10 转写新版本接入并取代 v1", st == 200 and rec_v2["outcome"] == "CREATED_SUPERSEDES")
    st, old_rec = call("GET", f"/api/ingest/recordings/{rec_id}")
    check("P10 唯一原证据未删除（旧版 SUPERSEDED 仍可读）",
          old_rec["recording"]["status"] == "SUPERSEDED" and len(old_rec["segments"]) == 2)
    st, mv3_now = call("GET", f"/api/privacy/views/{mv3['viewId']}")
    check("P10 转写更正使旧物化视图转 STALE（TRANSCRIPT_CORRECTED）",
          mv3_now["status"] == "STALE" and "TRANSCRIPT_CORRECTED" in mv3_now["invalidateReason"])
    st, old_view = call("POST", "/api/privacy/views/on-demand",
                        {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                         "caseScope": ["CASE-QA"], "recordingId": rec_id, "revealApprovalId": None})
    check("P10 旧版转写拒绝再出新视图（只能对 ACTIVE 版本生成）", st == 400)
    # 新版本片段需重新建立显式关联（关系不按对象自动继承）
    new_s1, new_s2 = rec_v2["segmentIds"]
    call("POST", "/api/ingest/events", event("EVT-LK3", "LINK_ASSERTED", "evt-lk3",
         {"segment_id": new_s1, "fact_key": "CMP-QA"}))
    call("POST", "/api/ingest/events", event("EVT-LK4", "LINK_ASSERTED", "evt-lk4",
         {"segment_id": new_s2, "fact_key": "CMP-QA"}))
    st, new_view = call("POST", "/api/privacy/views/on-demand",
                        {"role": "COMPLIANCE_OFFICER", "purpose": "COMPLAINT_REVIEW",
                         "caseScope": ["CASE-QA"], "recordingId": rec_v2["recordingId"],
                         "revealApprovalId": None})
    check("P10 对新 ACTIVE 版本可正常生成视图", st == 200 and new_view["status"] == "FRESH")

    # ---------- P11 全局下载留痕审计 ----------
    st, all_dl = call("GET", "/api/privacy/views/downloads/all")
    granted = [d for d in all_dl if d["granted"]]
    blocked_dl = [d for d in all_dl if not d["granted"]]
    check("P11 下载留痕同时记录放行与拦截",
          len(granted) >= 3 and len(blocked_dl) >= 4,
          f"granted={len(granted)} blocked={len(blocked_dl)}")
    blocked_reasons = " ".join(d["reason"] for d in blocked_dl)
    check("P11 拦截原因覆盖实体修正/规则修订/范围收窄",
          all(token in blocked_reasons
              for token in ("ENTITY_REVISED", "MASKING_RULE_REVISED", "CASE_SCOPE_NARROWED")),
          blocked_reasons)

    # ---------- 汇总 ----------
    failed = [r for r in RESULTS if not r[1]]
    print("\n===== 隐私视图域验收汇总 =====")
    print(f"通过 {len(RESULTS) - len(failed)}/{len(RESULTS)}")
    summary = {"base": BASE, "total": len(RESULTS), "passed": len(RESULTS) - len(failed),
               "failed": len(failed),
               "results": [{"name": n, "pass": c, "detail": d} for n, c, d in RESULTS]}
    with open("scripts/privacy-verification-result.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    if failed:
        for n, _, d in failed:
            print("FAILED:", n, d)
        return 1
    print("全部隐私视图域场景通过，结果已写入 scripts/privacy-verification-result.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())

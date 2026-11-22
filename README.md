# 通话保留与调阅数据服务（call-retention-service）

面向消费金融机构的合规证据服务：把客服热线、催收外呼、理财销售回访的录音与转写，
管理为能够解释 **“为何仍需保留、谁可调阅、何时允许处置”** 的证据。
技术栈：Java 25 + Spring Boot 3.5 + PostgreSQL 18（Spring Data JPA / springdoc-openapi）。

## 能力总览

| 域 | 说明 |
| --- | --- |
| 接入账 | 录音对象（标识、哈希、声道、时长、片段边界、业务/接收两类时间、来源修订链、幂等键）与结构化业务事件；相同来源版本+相同内容重复到达不重复建账/建义务，相同版本内容不同保留冲突待核 |
| 证据关系图 | 文件→片段→已分类字段→业务事实的显式边；只凭 `LINK_ASSERTED` 事件建边，不按账户相同归并案件 |
| 规则目录 | 适用事实、起算事件、保留期限、适用区间、用途、字段集合；多版本；追溯版本仅对适用区间内既有事实重放 |
| 义务解释（双路线） | 路线A：每次求值从冻结截点（事件折叠的世界+规则）遍历派生；路线B：不可变义务账+补偿记录（VOID/RECTIFY）。同一截点结论一致，代价不同（遍历范围 vs 补偿写入），见 `GET /api/obligations/compare` |
| 调阅 | 按角色/案件范围/用途投影字段，返回允许片段、字段及拒绝理由；快照固定证据水位、关系（水位隐含）与规则版本；历史复演不改旧回执；再次下载须过当前授权 |
| 处置 | 整对象候选（全部片段合格才可）；计划保存依赖版本与阻断原因；提交前重新核验；后到冻结/追溯更正撤销未执行候选，已执行则生成事实冲突升级记录（不伪称恢复原件） |
| 执行账 | 逐对象记录操作号、请求、确认、拒绝、结果未知；超时计 UNKNOWN 不计成功；重复回执不重复处置；结果未知凭原操作号核对 |
| 敏感实体定位器 | 在不可变原转写上按字符偏移定位身份证号、银行卡号、住址、健康情况；支持跨两段实体缝合（PREFIX/SUFFIX）与识别修正（只追加新版本、原转写不回写），逐处回链 segment/offset |
| 遮蔽规则版本库 | 遮蔽规则按 ruleCode+version 不可变版本化（全遮蔽/留尾号/留首尾），配置指纹入视图绑定；修订后旧版保留可回链 |
| 证据视图（双路线） | ON_DEMAND：调阅时从不可变原转写即时生成带规则版本的遮蔽视图（有生成等待、不留长期副本）；MATERIALIZED：常用角色视图预物化，FRESH 直返，规则修订/转写更正/识别修正/案件范围变化时准确失效（STALE）。每视图回链字段位置、规则版本与还原审批；唯一原证据仅 SUPERSEDED 不删除 |
| 范围校验与下载拦截 | 按角色/用途/案件目的（显式关系边，不按账户归并）决定入视图片段；物化视图失效、案件范围收窄、还原审批失效的新下载一律 403 拦截，并以独立事务留痕（放行/拦截均可审计） |
| 临时还原审批台 | 对已遮蔽实体的限时去遮蔽申请-审批；最小实体集合授权（不接受整录音通配）、有效期、撤销；撤销/过期使关联物化视图失效 |

## 本地启动

前置：JDK 25（`/opt/jdk-25`）、Maven 3.9（`/opt/maven`）、PostgreSQL 18（`127.0.0.1:5432`，库/用户/密码均为 `ccdev`）。

```bash
# 方式一：本地 Maven 运行
/opt/maven/bin/mvn -DskipTests package
/opt/jdk-25/bin/java -jar target/call-retention-service-1.0.0.jar

# 方式二：Docker Compose 一键启动（应用 + PostgreSQL）
docker compose up --build
```

启动后：

- 服务地址：`http://127.0.0.1:8080`
- Swagger UI：`http://127.0.0.1:8080/swagger-ui.html`（OpenAPI 3.x 文档：`/v3/api-docs`）
- 数据库连接可用环境变量覆盖：`DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASS`

## 测试账号（调阅角色）

服务无登录态，调阅接口以请求中的角色标识授权（虚构策略，见 `AccessPolicy`）：

| 角色 | 可及分类 | 可用用途 |
| --- | --- | --- |
| `COMPLIANCE_OFFICER` | 全部 | 全部 |
| `AUDITOR` | 全部 | AUDIT / COMPLIANCE |
| `COLLECTION_AGENT` | PUBLIC / GENERAL / PII_BASIC | COLLECTION |
| `SALES_SUPERVISOR` | PUBLIC / GENERAL / PII_BASIC | SALES_REVIEW |
| `CUSTOMER_SERVICE` | PUBLIC / GENERAL | CUSTOMER_CARE |
| `QUALITY_INSPECTOR` | PUBLIC / GENERAL（敏感实体一律经遮蔽视图） | COMPLAINT_REVIEW |

数据库账号（仅本地隔离开发用）：`ccdev / ccdev`。

## 验证方式（实际执行过的验证）

端到端验收脚本驱动全部虚构策略场景，**所有结论由接口对实际输入求值得出**：

```bash
# 服务启动后执行（脚本开头会调用 DELETE /api/admin/reset 清空演示数据）
python3 scripts/verify.py            # 或指定服务地址：python3 scripts/verify.py http://127.0.0.1:8080
```

最近一次在本容器的实际执行结果：**70/70 项通过**（明细落盘 `scripts/verification-result.json`）。覆盖场景：

1. **接入账**：幂等重放不重复建账；同版本不同内容 → 409 冲突待核；来源修订链 v2 取代 v1；事件补发新版本不产生重复义务。
2. **虚构保留策略**：销售片段自产品终止日（2021-06-30）起 6 年、催收片段自确认结案日（2022-03-31）起 3 年、保全至明确解除。2026-04-01 求值：360 秒录音前 180 秒（销售）保留至 2027-06-30，后 180 秒（催收）已于 2025-03-31 到期，**整原件不得处置**。
3. **候选与保全**：仅含已到期催收片段的录音先生成候选，模拟执行前收到投诉保全 → 候选被撤销并保留撤销原因，执行被阻止；保全解除后重新成为候选。
4. **执行账**：回执超时 → UNKNOWN（核对仍 UNKNOWN，不计成功）；迟到回执按原操作号落定 SUCCESS；重复回执不重复处置；终态后矛盾回执记录冲突升级。
5. **追溯与更正**：起算日期更正（2021-06-30→2019-06-30）使销售片段到期、整对象转合格；规则 v2 仅对 2023-03 后事实追溯（C3 改按 2 年，C4 在区间外仍按 3 年）；未确认起算事实给出未确定依据（PENDING，不提前到期），确认后落定。
6. **调阅**：角色/案件范围/用途投影（允许 AUDIO/TRANSCRIPT/CUSTOMER_NAME，ID_NUMBER/BANK_CARD 按分类拒绝）；同账户未关联电话不归并（NO_ACTIVE_LINK）；快照复演一致；案件范围收窄后旧快照复演仍一致、再次下载被当前授权拒绝（403），授权覆盖时可下载。
7. **已执行后冲突**：已处置对象上后到冻结 → 生成 `EXECUTED_DISPOSAL_FACT_CONFLICT` 升级记录，执行账保持 SUCCESS（不伪称恢复）。
8. **双路线一致性**：`GET /api/obligations/compare?asOf=2026-04-01` 全部 ACTIVE 对象结论一致；同次更正中路线A遍历全部片段（segmentsVisited/linksWalked/factsLoaded），路线B仅读账载条目并以补偿记录传播（VOID+RECTIFY，见 `GET /api/obligations/maintenance-log`）。

### 证据视图/隐私遮蔽域验证（本次新增）

```bash
# 独立脚本，开头同样调用 DELETE /api/admin/reset，可重复执行
python3 scripts/verify_privacy.py
```

最近一次在本容器的实际执行结果：**54/54 项通过**（明细落盘 `scripts/privacy-verification-result.json`）。覆盖：

1. **实体定位器（跨两段+识别修正）**：银行卡 `6222021234567890` 被切在片段边界，缝合为 PREFIX(段1)+SUFFIX(段2) 两条提及并可拼回完整值；身份证完整落段1且字符偏移回链正确；住址、健康情况定位到段2；识别修正追加 rev2 取代 rev1，原转写不回写、重复定位幂等。
2. **遮蔽完整性**：生成时逐处核验，身份证/卡号/住址/健康的原文与规范值不得出现在遮蔽文本中，任一明文泄露即拒绝出视图；卡号按规则留尾 4 位。
3. **版本绑定**：视图固定并回链遮蔽规则版本（含 configHash）与实体修订版本；规则在生成中修订至 v2（卡号改全遮蔽）后，旧按需视图仍保留 v1 回链，绑定 v1 的物化视图转 STALE。
4. **准确失效与新下载拦截**：识别修正（ENTITY_REVISED）、规则修订（MASKING_RULE_REVISED）、转写更正（TRANSCRIPT_CORRECTED）、案件范围收窄（CASE_SCOPE_NARROWED）均使相应物化视图失效或拦截下载（403），拦截以独立事务留痕；重新物化后恢复 FRESH。
5. **已解封案件随后收窄**：保全解除后仍可物化 FRESH 视图；案件范围收窄或换成不含原案件的范围后，新下载被拦截，范围仍覆盖时放行。
6. **临时还原审批台**：不接受无实体集合的通配申请；未批准/已撤销的审批不能生成还原视图；批准后授权实体在最小集合内临时还原为原文并回链审批单，未授权实体仍遮蔽。
7. **唯一原证据不删除**：转写新版本接入后旧版仅置 SUPERSEDED 仍可读；旧版拒绝再出新视图，只能对当前 ACTIVE 版本生成。

> 说明：本容器未安装 Docker，未实际执行 `docker compose`；Dockerfile/Compose 结构保持不变，应用已通过本地 Maven 打包（`mvn package`）与上述两套 HTTP 端到端脚本验证。

## 主要接口

| 方法/路径 | 说明 |
| --- | --- |
| `POST /api/ingest/recordings` | 接入模拟录音对象（片段边界、声道、时长、两类时间、哈希、幂等键） |
| `POST /api/ingest/events` | 接入业务事件（业务生效时间+平台接收时间），触发义务传播与候选撤销 |
| `GET /api/ingest/recordings` `/events` `/conflicts` | 接入账与冲突待核查询 |
| `POST /api/rules` `GET /api/rules` | 规则目录登记（版本/追溯）与查询 |
| `GET /api/graph/recordings/{id}` `/segments/{id}` `/facts` `/holds` | 证据关系图 |
| `GET /api/obligations/segments/{id}` `/recordings/{id}?asOf&route` | 义务求值（route=A 截点遍历 / B 义务账） |
| `GET /api/obligations/compare?asOf` | 双路线一致性与代价对比 |
| `GET /api/obligations/ledger` `/maintenance-log` | 义务账条目与补偿维护日志 |
| `POST /api/access/evaluations` | 调阅求值并固定快照（水位+规则版本） |
| `POST /api/access/snapshots/{id}/replay` `/download` | 历史复演 / 再次下载（过当前授权） |
| `POST /api/disposal/plans` | 生成处置计划（候选+阻断原因+依赖版本） |
| `POST /api/disposal/candidates/{id}/execute` | 模拟执行（提交前重新核验；simulate=CONFIRM/REJECT/TIMEOUT） |
| `POST /api/execution/receipts` `/reconcile` `GET /api/execution/ledger` `/escalations` | 存储回执端、凭原操作号对账、执行账、升级记录 |
| `POST /api/privacy/masking-rules` `GET /api/privacy/masking-rules` | 遮蔽规则版本库（不可变版本/修订史/配置指纹） |
| `POST /api/privacy/entities/recordings/{id}/locate` | 实体定位器：自动定位（含跨段缝合） |
| `POST /api/privacy/entities/recordings/{id}/corrections` | 识别修正：追加实体新版本取代旧版（原转写不回写） |
| `GET /api/privacy/entities/recordings/{id}` | 敏感实体清单（修正链 + 逐处字段位置） |
| `POST /api/privacy/views/on-demand` `/materialized` | 按需即时生成 / 预先物化证据视图（带规则版本与回链） |
| `GET /api/privacy/views` `/{id}` `/{id}/downloads` | 视图清单、详情（逐处遮蔽回链）、单视图下载留痕 |
| `POST /api/privacy/views/{id}/download` | 下载视图：失效/范围收窄/审批失效的新下载 403 拦截并留痕 |
| `POST /api/privacy/reveal-approvals` `/{no}/decision` `/{no}/revoke` | 临时还原申请、审批/驳回、撤销（关联视图同步失效） |
| `DELETE /api/admin/reset` | 演示数据重置（含隐私域六张表） |

## 设计要点

- **事件即事实来源**：`business_event` 按全局水位序列持久化，当前状态=折叠全部事件，历史复演=折叠水位前缀，同一套转移逻辑保证同截点结论一致。
- **规则选择语义**：版本可见需 `registeredSeq ≤ 水位` 且事实生效日落入适用区间，且（追溯生效 或 `registeredSeq ≤ 事实最近变更水位`）；同一 ruleCode 取最高满足版本。
- **到期语义**：到期日 = 起算日 + 保留年限；`asOf < 到期日` 为保留中，否则已到期；起算事实未确认时义务为 PENDING（未确定依据），阻断处置但不提前到期。
- **处置资格**：片段合格 ⟺ 无未到期/未定义务且未被未解除保全覆盖；整对象合格 ⟺ 全部片段合格。限制展示（调阅投影）与删除资格（义务求值）相互独立。
- **路线B 补偿**：义务账条目不可变；更正通过 VOID（引用原条目）与 RECTIFY 追加，日常候选查询直接读账，变更时按影响面精准传播。
- **隐私域不变量**：
  - **唯一原证据不删除**：转写更正只把旧录音置 `SUPERSEDED` 并接入新版本，旧片段与历史定位/视图均可读；新视图只能对当前 ACTIVE 版本生成。
  - **回链三要素**：每个视图的每处遮蔽都回链字段位置（segmentId/segmentSeq/startOffset/endOffset/matchMode）、遮蔽规则版本（ruleId/version/configHash）与临时还原审批（approvalNo，无则为空）。
  - **遮蔽完整性**：出视图前核验未还原实体的原文与规范值不得出现在遮蔽文本中，失败即拒绝；遮蔽动作是纯函数，同（原文, 规则配置）可逐字节复演。
  - **双路线失效语义**：ON_DEMAND 每次按当前水位即时重算、不做缓存复用（视图行仅作回链与审计，下载前同样过规则指纹/片段指纹/范围/审批复核，过时即拦截）；MATERIALIZED 仅在 FRESH 时直返，规则修订/识别修正/转写更正经领域事件同步置 STALE，案件范围收窄与审批失效在下载时防御性复核拦截，且下载前再次比对规则配置指纹与片段指纹。
  - **最小授权还原**：临时还原必须指定实体最小集合与有效期，审批仅在角色/用途/案件范围/录音一致且未过期、未撤销时有效；撤销/过期会使引用它的 FRESH 物化视图同步失效。
  - **拦截必留痕**：下载放行与拦截都经独立事务（REQUIRES_NEW）写入 `evidence_view_download`，拦截异常回滚主事务也不丢失审计。

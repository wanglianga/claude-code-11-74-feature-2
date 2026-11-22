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
| `DELETE /api/admin/reset` | 演示数据重置 |

## 设计要点

- **事件即事实来源**：`business_event` 按全局水位序列持久化，当前状态=折叠全部事件，历史复演=折叠水位前缀，同一套转移逻辑保证同截点结论一致。
- **规则选择语义**：版本可见需 `registeredSeq ≤ 水位` 且事实生效日落入适用区间，且（追溯生效 或 `registeredSeq ≤ 事实最近变更水位`）；同一 ruleCode 取最高满足版本。
- **到期语义**：到期日 = 起算日 + 保留年限；`asOf < 到期日` 为保留中，否则已到期；起算事实未确认时义务为 PENDING（未确定依据），阻断处置但不提前到期。
- **处置资格**：片段合格 ⟺ 无未到期/未定义务且未被未解除保全覆盖；整对象合格 ⟺ 全部片段合格。限制展示（调阅投影）与删除资格（义务求值）相互独立。
- **路线B 补偿**：义务账条目不可变；更正通过 VOID（引用原条目）与 RECTIFY 追加，日常候选查询直接读账，变更时按影响面精准传播。

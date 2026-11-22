package com.example.retention.disposal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 执行账与存储回执端：逐对象记录操作号、请求、确认、拒绝与结果未知。 */
@RestController
@RequestMapping("/api/execution")
@Tag(name = "执行对账", description = "存储回执端、执行账、凭原操作号核对、升级记录")
public class ExecutionController {

    private final DisposalService disposalService;
    private final ExecutionLedgerRepository ledgerRepository;
    private final EscalationRecordRepository escalationRepository;

    public ExecutionController(DisposalService disposalService,
                               ExecutionLedgerRepository ledgerRepository,
                               EscalationRecordRepository escalationRepository) {
        this.disposalService = disposalService;
        this.ledgerRepository = ledgerRepository;
        this.escalationRepository = escalationRepository;
    }

    public record ReceiptRequest(String operationNo, String result, Map<String, Object> payload) {
    }

    @PostMapping("/receipts")
    @Operation(summary = "存储回执端：确认/拒绝落定；重复回执不重复处置；矛盾回执记录冲突")
    public Map<String, Object> receipt(@RequestBody ReceiptRequest req) {
        return disposalService.receiveReceipt(req.operationNo(), req.result(), req.payload());
    }

    public record ReconcileRequest(String operationNo) {
    }

    @PostMapping("/reconcile")
    @Operation(summary = "对账：结果未知须用原操作号核对；核对不制造结果")
    public Map<String, Object> reconcile(@RequestBody ReconcileRequest req) {
        return disposalService.reconcile(req.operationNo());
    }

    @GetMapping("/ledger")
    @Operation(summary = "执行账清单")
    public List<ExecutionLedger> ledger() {
        return ledgerRepository.findAll();
    }

    @GetMapping("/escalations")
    @Operation(summary = "事实冲突与升级记录")
    public List<EscalationRecord> escalations() {
        return escalationRepository.findAll();
    }
}

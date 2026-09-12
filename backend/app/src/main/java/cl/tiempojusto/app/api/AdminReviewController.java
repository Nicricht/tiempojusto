package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.AdminReviewApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminReviewController {
    private final AdminReviewApplicationService admin;
    private final ActorContext actorContext;

    public AdminReviewController(AdminReviewApplicationService admin, ActorContext actorContext) {
        this.admin = admin;
        this.actorContext = actorContext;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestParam(defaultValue = "") String query,
                                            HttpServletRequest request) {
        return admin.users(actorContext.requireActor(request), query);
    }

    @GetMapping("/auctions/{auctionId}")
    public Map<String, Object> auction(@PathVariable UUID auctionId, HttpServletRequest request) {
        return admin.auctionAudit(actorContext.requireActor(request), auctionId);
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> session(@PathVariable UUID sessionId, HttpServletRequest request) {
        return admin.sessionAudit(actorContext.requireActor(request), sessionId);
    }

    @GetMapping("/human-review-queue")
    public List<Map<String, Object>> queue(HttpServletRequest request) {
        return admin.queue(actorContext.requireActor(request));
    }

    @PostMapping("/human-review-queue/{taskId}/claim")
    public Map<String, Object> claim(@PathVariable UUID taskId, HttpServletRequest request) {
        return admin.claim(actorContext.requireActor(request), taskId);
    }

    @GetMapping("/safety-cases/{caseId}")
    public Map<String, Object> caseDetail(@PathVariable UUID caseId, HttpServletRequest request) {
        return admin.caseDetail(actorContext.requireActor(request), caseId);
    }

    @GetMapping("/safety-cases/{caseId}/evidence-timeline")
    public List<Map<String, Object>> evidenceTimeline(@PathVariable UUID caseId, HttpServletRequest request) {
        return admin.evidenceTimeline(actorContext.requireActor(request), caseId);
    }

    @PostMapping("/safety-cases/{caseId}/decision")
    public Map<String, Object> decide(@PathVariable UUID caseId,
                                      @RequestBody AdminReviewApplicationService.DecisionRequest body,
                                      HttpServletRequest request) {
        return admin.decide(actorContext.requireActor(request), caseId, body);
    }

    @GetMapping("/appeals")
    public List<Map<String, Object>> appeals(HttpServletRequest request) {
        return admin.appeals(actorContext.requireActor(request));
    }

    @PostMapping("/appeals/{appealId}/resolve")
    public Map<String, Object> resolveAppeal(@PathVariable UUID appealId,
                                             @RequestBody AdminReviewApplicationService.AppealResolutionRequest body,
                                             HttpServletRequest request) {
        return admin.resolveAppeal(actorContext.requireActor(request), appealId, body);
    }

    @GetMapping("/risk-signals")
    public List<Map<String, Object>> riskSignals(@RequestParam(required = false) UUID userId,
                                                  HttpServletRequest request) {
        return admin.riskSignals(actorContext.requireActor(request), userId);
    }

    @GetMapping("/finance/payouts/{payoutId}")
    public Map<String, Object> payoutAudit(@PathVariable UUID payoutId, HttpServletRequest request) {
        return admin.payoutAudit(actorContext.requireActor(request), payoutId);
    }

    @GetMapping("/finance/payouts/{payoutId}/holds")
    public List<Map<String, Object>> payoutHolds(@PathVariable UUID payoutId, HttpServletRequest request) {
        return admin.payoutHolds(actorContext.requireActor(request), payoutId);
    }

    @PostMapping("/finance/payouts/{payoutId}/holds")
    public Map<String, Object> createPayoutHold(@PathVariable UUID payoutId,
                                                @RequestBody AdminReviewApplicationService.PayoutHoldRequest body,
                                                HttpServletRequest request) {
        return admin.createPayoutHold(actorContext.requireActor(request), payoutId, body);
    }

    @PostMapping("/finance/payouts/{payoutId}/holds/{holdId}/release")
    public Map<String, Object> releasePayoutHold(@PathVariable UUID payoutId,
                                                 @PathVariable UUID holdId,
                                                 @RequestBody AdminReviewApplicationService.PayoutHoldReleaseRequest body,
                                                 HttpServletRequest request) {
        return admin.releasePayoutHold(actorContext.requireActor(request), payoutId, holdId, body);
    }

    @GetMapping("/finance/ledger/{transactionId}")
    public List<Map<String, Object>> ledgerAudit(@PathVariable UUID transactionId, HttpServletRequest request) {
        return admin.ledgerAudit(actorContext.requireActor(request), transactionId);
    }

    @GetMapping("/audit-log")
    public List<Map<String, Object>> auditLog(@RequestParam(required = false) UUID caseId,
                                               HttpServletRequest request) {
        return admin.auditLog(actorContext.requireActor(request), caseId);
    }

    @GetMapping("/admin-actions")
    public List<Map<String, Object>> adminActions(@RequestParam(required = false) UUID targetId,
                                                   HttpServletRequest request) {
        return admin.adminActions(actorContext.requireActor(request), targetId);
    }

    @RequestMapping(value = "/finance/**", method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<Map<String, String>> rejectFinanceMutation() {
        return ResponseEntity.status(405).body(Map.of(
                "code", "ADMIN_FINANCE_READ_ONLY",
                "message", "Las herramientas financieras de Admin son solo lectura salvo el flujo auditado de payout holds."
        ));
    }
}

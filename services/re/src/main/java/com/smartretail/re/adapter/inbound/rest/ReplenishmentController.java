package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.adapter.inbound.rest.generated.api.ReplenishmentOrdersApi;
import com.smartretail.re.adapter.inbound.rest.generated.model.ApproveRequest;
import com.smartretail.re.adapter.inbound.rest.generated.model.ManualReplenishmentRequest;
import com.smartretail.re.adapter.inbound.rest.generated.model.PurchaseOrder;
import com.smartretail.re.adapter.inbound.rest.generated.model.PurchaseOrderPage;
import com.smartretail.re.adapter.inbound.rest.generated.model.RejectRequest;
import com.smartretail.re.adapter.inbound.rest.generated.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.port.inbound.ApprovePurchaseOrderPort;
import com.smartretail.re.port.inbound.RejectPurchaseOrderPort;
import com.smartretail.re.port.inbound.TriggerManualReplenishmentPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@Tag(name = "replenishment-orders", description = "Purchase order lifecycle management")
public class ReplenishmentController implements ReplenishmentOrdersApi {

    private static final Set<String> PLANNER_ROLES = Set.of("SC_PLANNER", "ADMIN");

    private final ReplenishmentRepositoryPort repo;
    private final ApprovePurchaseOrderPort approvePort;
    private final RejectPurchaseOrderPort rejectPort;
    private final TriggerManualReplenishmentPort triggerPort;
    private final ReplenishmentResponseMapper mapper;
    private final HttpServletRequest httpRequest;

    public ReplenishmentController(ReplenishmentRepositoryPort repo,
                                   ApprovePurchaseOrderPort approvePort,
                                   RejectPurchaseOrderPort rejectPort,
                                   TriggerManualReplenishmentPort triggerPort,
                                   ReplenishmentResponseMapper mapper,
                                   HttpServletRequest httpRequest) {
        this.repo = repo;
        this.approvePort = approvePort;
        this.rejectPort = rejectPort;
        this.triggerPort = triggerPort;
        this.mapper = mapper;
        this.httpRequest = httpRequest;
    }

    @Override
    public ResponseEntity<PurchaseOrder> createPurchaseOrder(ManualReplenishmentRequest body) {
        var po = triggerPort.trigger(
                body.getSkuId(),
                body.getDcId(),
                body.getQuantity(),
                body.getNotes()
        );
        var lineItems = repo.findLineItemsByPoId(po.getPoId());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toApiModel(po, lineItems));
    }

    @Override
    public ResponseEntity<PurchaseOrderPage> listPurchaseOrders(
            WorkflowStatus status, String dcId, String skuId, Integer page, Integer size) {

        String statusName = status != null ? status.getValue() : null;
        var orders = repo.findOrders(statusName, dcId, skuId, page, size);
        long total = repo.countOrders(statusName, dcId, skuId);

        PurchaseOrderPage response = new PurchaseOrderPage();
        response.setOrders(orders.stream().map(mapper::toApiModel).toList());
        response.setPage(page);
        response.setSize(size);
        response.setTotalElements(total);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<PurchaseOrder> getPurchaseOrder(UUID poId) {
        var po = repo.findById(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));
        var lineItems = repo.findLineItemsByPoId(poId);
        return ResponseEntity.ok(mapper.toApiModel(po, lineItems));
    }

    @Override
    public ResponseEntity<PurchaseOrder> approvePurchaseOrder(UUID poId,
                                                               UUID xIdempotencyKey,
                                                               ApproveRequest body) {
        requireRole(PLANNER_ROLES);
        String approvedBy = extractPrincipal();
        var approved = approvePort.approve(poId, body.getVersion(), approvedBy);
        var lineItems = repo.findLineItemsByPoId(poId);
        return ResponseEntity.ok(mapper.toApiModel(approved, lineItems));
    }

    @Override
    public ResponseEntity<PurchaseOrder> rejectPurchaseOrder(UUID poId,
                                                              UUID xIdempotencyKey,
                                                              RejectRequest body) {
        requireRole(PLANNER_ROLES);
        String rejectedBy = extractPrincipal();
        var rejected = rejectPort.reject(poId, body.getVersion(), rejectedBy, body.getRejectionReason());
        var lineItems = repo.findLineItemsByPoId(poId);
        return ResponseEntity.ok(mapper.toApiModel(rejected, lineItems));
    }

    private void requireRole(Set<String> allowed) {
        if (extractRoles().stream().noneMatch(allowed::contains)) {
            throw new AccessDeniedException("Insufficient role for this operation");
        }
    }

    /**
     * Local mode: role from X-Dev-Role header (defaults to SC_PLANNER).
     * AWS mode:   roles from cognito:groups JWT claim.
     */
    private Set<String> extractRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            List<String> groups = jwt.getClaimAsStringList("cognito:groups");
            return groups != null ? Set.copyOf(groups) : Set.of();
        }
        String header = httpRequest.getHeader("X-Dev-Role");
        return Set.of(header != null ? header : "SC_PLANNER");
    }

    private String extractPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return "local-user";
        }
        return auth.getName();
    }
}

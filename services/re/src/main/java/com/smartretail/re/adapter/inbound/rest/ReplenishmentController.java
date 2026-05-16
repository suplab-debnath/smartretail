package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.adapter.inbound.rest.generated.api.ReplenishmentOrdersApi;
import com.smartretail.re.adapter.inbound.rest.generated.model.ApproveRequest;
import com.smartretail.re.adapter.inbound.rest.generated.model.PurchaseOrder;
import com.smartretail.re.adapter.inbound.rest.generated.model.PurchaseOrderPage;
import com.smartretail.re.adapter.inbound.rest.generated.model.RejectRequest;
import com.smartretail.re.adapter.inbound.rest.generated.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.port.inbound.ApprovePurchaseOrderPort;
import com.smartretail.re.port.inbound.RejectPurchaseOrderPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Tag(name = "replenishment-orders", description = "Purchase order lifecycle management")
public class ReplenishmentController implements ReplenishmentOrdersApi {

    private final ReplenishmentRepositoryPort repo;
    private final ApprovePurchaseOrderPort approvePort;
    private final RejectPurchaseOrderPort rejectPort;
    private final ReplenishmentResponseMapper mapper;

    public ReplenishmentController(ReplenishmentRepositoryPort repo,
                                   ApprovePurchaseOrderPort approvePort,
                                   RejectPurchaseOrderPort rejectPort,
                                   ReplenishmentResponseMapper mapper) {
        this.repo = repo;
        this.approvePort = approvePort;
        this.rejectPort = rejectPort;
        this.mapper = mapper;
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
        String approvedBy = extractPrincipal();
        var approved = approvePort.approve(poId, body.getVersion(), approvedBy);
        var lineItems = repo.findLineItemsByPoId(poId);
        return ResponseEntity.ok(mapper.toApiModel(approved, lineItems));
    }

    @Override
    public ResponseEntity<PurchaseOrder> rejectPurchaseOrder(UUID poId,
                                                              UUID xIdempotencyKey,
                                                              RejectRequest body) {
        String rejectedBy = extractPrincipal();
        var rejected = rejectPort.reject(poId, body.getVersion(), rejectedBy, body.getRejectionReason());
        var lineItems = repo.findLineItemsByPoId(poId);
        return ResponseEntity.ok(mapper.toApiModel(rejected, lineItems));
    }

    /**
     * Extracts the subject (username/email) from the JWT token in the security context.
     * In local mode (permitAll), returns "local-user" as a fallback.
     */
    private String extractPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return "local-user";
        }
        return auth.getName();
    }
}

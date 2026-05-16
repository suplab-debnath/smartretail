package com.smartretail.sup.adapter.inbound.rest;

import com.smartretail.sup.adapter.in.web.generated.api.SupplierOrdersApi;
import com.smartretail.sup.adapter.in.web.generated.model.ShipmentStatus;
import com.smartretail.sup.adapter.in.web.generated.model.SupplierOrder;
import com.smartretail.sup.adapter.in.web.generated.model.SupplierOrderListResponse;
import com.smartretail.sup.domain.model.SupplierOrderList;
import com.smartretail.sup.port.inbound.SupplierOrderQueryPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * Supplier order tracking endpoint — SUP service, port 8085.
 * Reads from supplier schema only — no cross-schema SQL joins.
 */
@RestController
@Tag(name = "supplier-orders", description = "Supplier order tracking with shipment progress")
public class SupplierOrderController implements SupplierOrdersApi {

    private static final Set<String> ALLOWED_ROLES = Set.of("SC_PLANNER", "ADMIN");

    private final SupplierOrderQueryPort supplierOrderQueryPort;

    @Autowired
    private HttpServletRequest httpRequest;

    public SupplierOrderController(SupplierOrderQueryPort supplierOrderQueryPort) {
        this.supplierOrderQueryPort = supplierOrderQueryPort;
    }

    @Override
    public ResponseEntity<SupplierOrderListResponse> getSupplierOrders(String status) {

        if (!hasAnyRole(ALLOWED_ROLES)) return ResponseEntity.status(403).build();

        SupplierOrderList result = supplierOrderQueryPort.getSupplierOrders(status);

        List<SupplierOrder> orders = result.orders().stream()
                .map(o -> {
                    SupplierOrder order = new SupplierOrder(
                            o.supplierPoId(),
                            o.poId(),
                            o.supplierId(),
                            o.supplierName(),
                            o.skuId(),
                            o.dcId(),
                            o.quantity(),
                            ShipmentStatus.fromValue(o.shipmentStatus())
                    );
                    if (o.confirmedAt() != null)  order.setConfirmedAt(o.confirmedAt().atOffset(ZoneOffset.UTC));
                    if (o.dispatchedAt() != null) order.setDispatchedAt(o.dispatchedAt().atOffset(ZoneOffset.UTC));
                    if (o.eta() != null)          order.setEta(o.eta());
                    if (o.lastUpdateAt() != null) order.setLastUpdateAt(o.lastUpdateAt().atOffset(ZoneOffset.UTC));
                    return order;
                })
                .toList();

        SupplierOrderListResponse response = new SupplierOrderListResponse(
                orders,
                result.dataFreshness().atOffset(ZoneOffset.UTC)
        );

        return ResponseEntity.ok(response);
    }

    private boolean hasAnyRole(Set<String> allowed) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            List<String> groups = jwt.getClaimAsStringList("cognito:groups");
            if (groups != null) return groups.stream().anyMatch(allowed::contains);
            return false;
        }
        // Local dev fallback: X-Dev-Role header
        String header = httpRequest.getHeader("X-Dev-Role");
        return allowed.contains(header != null ? header : "UNKNOWN");
    }
}

package com.smartretail.sup.adapter.inbound.rest;

import com.smartretail.sup.adapter.in.web.generated.api.SupplierOrdersApi;
import com.smartretail.sup.adapter.in.web.generated.model.CreateSupplierOrderRequest;
import com.smartretail.sup.adapter.in.web.generated.model.CreateSupplierOrderResponse;
import com.smartretail.sup.adapter.in.web.generated.model.ShipmentStatus;
import com.smartretail.sup.adapter.in.web.generated.model.SupplierOrderListResponse;
import com.smartretail.sup.port.inbound.CreateSupplierOrderPort;
import com.smartretail.sup.port.inbound.SupplierOrderQueryPort;
import com.smartretail.sup.port.outbound.SupplierOrderWritePort.DuplicatePoException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@Tag(name = "supplier-orders", description = "Supplier order tracking with shipment progress")
public class SupplierOrderController implements SupplierOrdersApi {

    private static final Set<String> ALLOWED_ROLES = Set.of("SC_PLANNER", "ADMIN", "SUPPLIER_ADMIN");

    private final SupplierOrderQueryPort supplierOrderQueryPort;
    private final CreateSupplierOrderPort createSupplierOrderPort;
    private final SupplierOrderResponseMapper supplierOrderResponseMapper;

    @Autowired
    private HttpServletRequest httpRequest;

    public SupplierOrderController(SupplierOrderQueryPort supplierOrderQueryPort,
                                   CreateSupplierOrderPort createSupplierOrderPort,
                                   SupplierOrderResponseMapper supplierOrderResponseMapper) {
        this.supplierOrderQueryPort = supplierOrderQueryPort;
        this.createSupplierOrderPort = createSupplierOrderPort;
        this.supplierOrderResponseMapper = supplierOrderResponseMapper;
    }

    @Override
    public ResponseEntity<SupplierOrderListResponse> getSupplierOrders(ShipmentStatus status) {
        if (!hasAnyRole(ALLOWED_ROLES)) return ResponseEntity.status(403).build();

        return ResponseEntity.ok(
                supplierOrderResponseMapper.toResponse(
                        supplierOrderQueryPort.getSupplierOrders(
                                status != null ? status.getValue() : null))
        );
    }

    @Override
    public ResponseEntity<CreateSupplierOrderResponse> createSupplierOrder(CreateSupplierOrderRequest request) {
        if (!hasAnyRole(ALLOWED_ROLES)) return ResponseEntity.status(403).build();

        try {
            UUID supplierPoId = createSupplierOrderPort.createSupplierOrder(
                    new CreateSupplierOrderPort.Command(
                            request.getPoId(),
                            request.getSupplierId(),
                            request.getSkuId(),
                            request.getDcId(),
                            request.getQuantity()));
            return ResponseEntity.status(201).body(new CreateSupplierOrderResponse(supplierPoId));
        } catch (DuplicatePoException e) {
            return ResponseEntity.status(409).build();
        }
    }

    private boolean hasAnyRole(Set<String> allowed) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            List<String> groups = jwt.getClaimAsStringList("cognito:groups");
            if (groups != null) return groups.stream().anyMatch(allowed::contains);
            return false;
        }
        String header = httpRequest.getHeader("X-Dev-Role");
        return allowed.contains(header != null ? header : "UNKNOWN");
    }
}

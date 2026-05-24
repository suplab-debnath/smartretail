package com.smartretail.sup.adapter.inbound.rest;

import com.smartretail.sup.adapter.in.web.generated.api.SuppliersApi;
import com.smartretail.sup.adapter.in.web.generated.model.SupplierListResponse;
import com.smartretail.sup.adapter.in.web.generated.model.SupplierRecord;
import com.smartretail.sup.port.inbound.SupplierQueryPort;
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

@RestController
@Tag(name = "suppliers", description = "Supplier reference data")
public class SupplierController implements SuppliersApi {

    private static final Set<String> ALLOWED_ROLES = Set.of("SC_PLANNER", "ADMIN", "SUPPLIER_ADMIN");

    private final SupplierQueryPort supplierQueryPort;

    @Autowired
    private HttpServletRequest httpRequest;

    public SupplierController(SupplierQueryPort supplierQueryPort) {
        this.supplierQueryPort = supplierQueryPort;
    }

    @Override
    public ResponseEntity<SupplierListResponse> getSuppliers() {
        if (!hasAnyRole(ALLOWED_ROLES)) return ResponseEntity.status(403).build();

        List<SupplierRecord> records = supplierQueryPort.getSuppliers().suppliers().stream()
                .map(s -> new SupplierRecord(s.supplierId(), s.supplierName()))
                .toList();
        return ResponseEntity.ok(new SupplierListResponse(records));
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

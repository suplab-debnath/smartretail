package com.smartretail.pps.adapter.inbound.rest;

import com.smartretail.pps.adapter.in.web.generated.api.PromotionSchedulesApi;
import com.smartretail.pps.adapter.in.web.generated.model.PromotionScheduleListResponse;
import com.smartretail.pps.adapter.in.web.generated.model.PromotionStatus;
import com.smartretail.pps.port.inbound.PromotionQueryPort;
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
@Tag(name = "promotion-schedules", description = "Active promotion schedules for forecast uplift signals")
public class PromotionController implements PromotionSchedulesApi {

    private static final Set<String> ALLOWED_ROLES = Set.of("SC_PLANNER", "ADMIN");

    private final PromotionQueryPort promotionQueryPort;
    private final PromotionResponseMapper promotionResponseMapper;

    @Autowired
    private HttpServletRequest httpRequest;

    public PromotionController(PromotionQueryPort promotionQueryPort,
                               PromotionResponseMapper promotionResponseMapper) {
        this.promotionQueryPort = promotionQueryPort;
        this.promotionResponseMapper = promotionResponseMapper;
    }

    @Override
    public ResponseEntity<PromotionScheduleListResponse> getPromotionSchedules(PromotionStatus status) {
        if (!hasAnyRole(ALLOWED_ROLES)) return ResponseEntity.status(403).build();

        return ResponseEntity.ok(
                promotionResponseMapper.toResponse(
                        promotionQueryPort.getPromotionSchedules(
                                status != null ? status.getValue() : null))
        );
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

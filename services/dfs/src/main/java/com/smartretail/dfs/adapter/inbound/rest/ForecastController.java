package com.smartretail.dfs.adapter.inbound.rest;

import com.smartretail.dfs.adapter.in.web.generated.api.ForecastApi;
import com.smartretail.dfs.adapter.in.web.generated.model.ForecastDataResponse;
import com.smartretail.dfs.domain.model.ForecastData;
import com.smartretail.dfs.port.inbound.ForecastQueryPort;
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

/**
 * Demand forecast read endpoint — DFS service, port 8084.
 * Queries forecasting schema only via ars_readonly DB user.
 */
@RestController
@Tag(name = "forecast", description = "Demand forecast bands per SKU × DC")
public class ForecastController implements ForecastApi {

    private static final Set<String> ALLOWED_ROLES = Set.of("SC_PLANNER", "ADMIN");

    private final ForecastQueryPort forecastQueryPort;
    private final ForecastResponseMapper forecastResponseMapper;

    @Autowired
    private HttpServletRequest httpRequest;

    public ForecastController(ForecastQueryPort forecastQueryPort,
                               ForecastResponseMapper forecastResponseMapper) {
        this.forecastQueryPort = forecastQueryPort;
        this.forecastResponseMapper = forecastResponseMapper;
    }

    @Override
    public ResponseEntity<ForecastDataResponse> getForecastBands(
            String skuId, String dcId, Integer horizonDays) {

        if (!hasAnyRole(ALLOWED_ROLES)) return ResponseEntity.status(403).build();

        int horizon = horizonDays != null ? horizonDays : 30;

        ForecastData data = forecastQueryPort.getForecast(skuId, dcId, horizon);

        if (data.bands().isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(forecastResponseMapper.toResponse(data));
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

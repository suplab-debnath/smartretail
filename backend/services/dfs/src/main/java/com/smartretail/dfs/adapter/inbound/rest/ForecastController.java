package com.smartretail.dfs.adapter.inbound.rest;

import com.smartretail.dfs.adapter.in.web.generated.api.ForecastApi;
import com.smartretail.dfs.adapter.in.web.generated.model.ForecastDataResponse;
import com.smartretail.dfs.adapter.in.web.generated.model.IngestForecastResultsRequest;
import com.smartretail.dfs.adapter.in.web.generated.model.IngestForecastResultsResponse;
import com.smartretail.dfs.domain.model.ForecastData;
import com.smartretail.dfs.domain.model.ForecastIngestionResult;
import com.smartretail.dfs.domain.model.ForecastRow;
import com.smartretail.dfs.port.inbound.ForecastQueryPort;
import com.smartretail.dfs.port.inbound.ForecastWritePort;
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
import java.util.UUID;

/**
 * Demand forecast endpoints — DFS service, port 8084.
 * Read path: getForecastBands (SC_PLANNER, ADMIN).
 * Write path: ingestForecastResults (Batch Post-Processor Lambda, internal-only).
 */
@RestController
@Tag(name = "forecast", description = "Demand forecast bands per SKU × DC")
public class ForecastController implements ForecastApi {

    private static final Set<String> READ_ROLES = Set.of("SC_PLANNER", "ADMIN");

    private final ForecastQueryPort forecastQueryPort;
    private final ForecastResponseMapper forecastResponseMapper;
    private final ForecastWritePort forecastWritePort;

    @Autowired
    private HttpServletRequest httpRequest;

    public ForecastController(ForecastQueryPort forecastQueryPort,
                               ForecastResponseMapper forecastResponseMapper,
                               ForecastWritePort forecastWritePort) {
        this.forecastQueryPort = forecastQueryPort;
        this.forecastResponseMapper = forecastResponseMapper;
        this.forecastWritePort = forecastWritePort;
    }

    @Override
    public ResponseEntity<ForecastDataResponse> getForecastBands(
            String skuId, String dcId, Integer horizonDays) {

        if (!hasAnyRole(READ_ROLES)) return ResponseEntity.status(403).build();

        int horizon = horizonDays != null ? horizonDays : 30;

        ForecastData data = forecastQueryPort.getForecast(skuId, dcId, horizon);

        if (data.bands().isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(forecastResponseMapper.toResponse(data));
    }

    @Override
    public ResponseEntity<IngestForecastResultsResponse> ingestForecastResults(
            UUID runId, IngestForecastResultsRequest request) {

        List<ForecastRow> rows = request.getRows().stream()
                .map(r -> new ForecastRow(
                        r.getSkuId(),
                        r.getDcId(),
                        r.getForecastDate(),
                        r.getHorizonDays(),
                        r.getP10(),
                        r.getP50(),
                        r.getP90()))
                .toList();

        ForecastIngestionResult result = forecastWritePort.ingest(runId, rows);

        IngestForecastResultsResponse response = new IngestForecastResultsResponse(
                result.runId(),
                result.rowsInserted(),
                result.ingestedAt().atOffset(ZoneOffset.UTC));

        return ResponseEntity.status(201).body(response);
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

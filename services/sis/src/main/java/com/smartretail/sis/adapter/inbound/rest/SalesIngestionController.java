package com.smartretail.sis.adapter.inbound.rest;

import com.smartretail.sis.adapter.in.web.generated.api.IngestApi;
import com.smartretail.sis.adapter.in.web.generated.model.IngestResponse;
import com.smartretail.sis.adapter.in.web.generated.model.SalesEventRequest;
import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.inbound.SalesEventPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "ingest", description = "Sales transaction event ingestion")
public class SalesIngestionController implements IngestApi {

    private final SalesEventPort salesEventPort;
    private final SalesEventMapper salesEventMapper;

    public SalesIngestionController(SalesEventPort salesEventPort, SalesEventMapper salesEventMapper) {
        this.salesEventPort = salesEventPort;
        this.salesEventMapper = salesEventMapper;
    }

    @Override
    public ResponseEntity<IngestResponse> ingestSalesEvent(SalesEventRequest salesEventRequest) {
        SalesTransaction transaction = salesEventMapper.toDomain(salesEventRequest);
        IngestionResult result = salesEventPort.ingest(transaction);

        return switch (result) {
            case IngestionResult.Accepted a ->
                ResponseEntity.accepted()
                    .body(new IngestResponse(a.transactionId(), IngestResponse.StatusEnum.ACCEPTED));
            case IngestionResult.Duplicate d ->
                ResponseEntity.status(409)
                    .body(null);
        };
    }
}

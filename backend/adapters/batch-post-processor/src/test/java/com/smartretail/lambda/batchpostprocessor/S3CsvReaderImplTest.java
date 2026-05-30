package com.smartretail.lambda.batchpostprocessor;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3CsvReaderImplTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private LambdaLogger logger;

    private S3CsvReaderImpl s3CsvReader;

    @BeforeEach
    void setUp() {
        s3CsvReader = new S3CsvReaderImpl(s3Client);
    }

    @Test
    void shouldParseValidCsvSuccessfully() {
        String csvData = "# comment line here\n" +
                "\n" + // empty line
                "SKU-001,DC-LONDON,2026-06-01,30,80,100,130\n" +
                "  SKU-002 , DC-PARIS , 2026-06-02 , 15 , 50 , 70 , 90 \n"; // with spaces

        ResponseInputStream<GetObjectResponse> s3Stream = createS3Stream(csvData);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3Stream);

        List<ForecastRowPayload> rows = s3CsvReader.readRows("my-bucket", "my-key", logger);

        assertNotNull(rows);
        assertEquals(2, rows.size());

        ForecastRowPayload r1 = rows.get(0);
        assertEquals("SKU-001", r1.skuId());
        assertEquals("DC-LONDON", r1.dcId());
        assertEquals(LocalDate.of(2026, 6, 1), r1.forecastDate());
        assertEquals(30, r1.horizonDays());
        assertEquals(80, r1.p10());
        assertEquals(100, r1.p50());
        assertEquals(130, r1.p90());

        ForecastRowPayload r2 = rows.get(1);
        assertEquals("SKU-002", r2.skuId());
        assertEquals("DC-PARIS", r2.dcId());
        assertEquals(LocalDate.of(2026, 6, 2), r2.forecastDate());
        assertEquals(15, r2.horizonDays());
        assertEquals(50, r2.p10());
        assertEquals(70, r2.p50());
        assertEquals(90, r2.p90());
    }

    @Test
    void shouldPropagateExceptionOnS3Failure() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(SdkException.builder().message("S3 access denied").build());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> s3CsvReader.readRows("my-bucket", "my-key", logger));

        assertTrue(ex.getMessage().contains("Failed to read S3 object"));
        assertTrue(ex.getCause() instanceof SdkException);
    }

    private ResponseInputStream<GetObjectResponse> createS3Stream(String csvData) {
        GetObjectResponse response = GetObjectResponse.builder().build();
        return new ResponseInputStream<>(
                response,
                AbortableInputStream.create(new ByteArrayInputStream(csvData.getBytes(StandardCharsets.UTF_8))));
    }
}

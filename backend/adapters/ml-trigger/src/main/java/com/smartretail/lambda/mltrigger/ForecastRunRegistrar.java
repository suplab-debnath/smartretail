package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.util.UUID;

/** Port: registers a new forecast run in DFS and returns its generated runId. */
interface ForecastRunRegistrar {
    UUID registerRun(String triggeredBy, LambdaLogger logger);
}

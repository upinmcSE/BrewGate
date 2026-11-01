package github.upinmcse.brewgate.middleware.metric;

import com.sun.net.httpserver.HttpHandler;
import github.upinmcse.brewgate.middleware.HttpMiddleware;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class MetricsMiddleware implements HttpMiddleware {
    private static final Logger log = LoggerFactory.getLogger(MetricsMiddleware.class);
    private final String serviceName;
    private final PrometheusMeterRegistry registry;

    public MetricsMiddleware(String serviceName, PrometheusMeterRegistry registry) {
        this.serviceName = serviceName;
        this.registry = registry;
    }

    @Override
    public HttpHandler apply(HttpHandler next) {
        return exchange -> {
            long startTimeNanos = System.nanoTime();
            int statusCode = 500;
            try {
                next.handle(exchange);

                int actualCode = exchange.getResponseCode();
                statusCode = actualCode > 0 ? actualCode : 200;
            } catch (Exception e) {
                log.error("Unhandled exception in request pipeline for service '{}': {}", serviceName, e.getMessage());
                throw e;
            } finally {
                long durationNanos = System.nanoTime() - startTimeNanos;

                // 1. Increment the counter for total requests, tagged by service and HTTP status code
                Counter.builder("http_requests_total")
                        .description("Total number of HTTP requests processed by the gateway")
                        .tag("service", serviceName)
                        .tag("status", String.valueOf(statusCode))
                        .register(registry)
                        .increment();

                // 2. Record request processing time (latency timer) with P50, P90, and P99 percentiles
                Timer.builder("http_request_duration_seconds")
                        .description("HTTP request execution latency in seconds")
                        .tag("service", serviceName)
                        .publishPercentiles(0.5, 0.9, 0.99)
                        .minimumExpectedValue(Duration.ofMillis(1))
                        .maximumExpectedValue(Duration.ofSeconds(10))
                        .register(registry)
                        .record(durationNanos, TimeUnit.NANOSECONDS);

                log.debug("Metrics recorded for '{}' [Status: {}, Latency: {} ms]",
                        serviceName, statusCode, durationNanos / 1_000_000.0);
            }
        };
    }
}

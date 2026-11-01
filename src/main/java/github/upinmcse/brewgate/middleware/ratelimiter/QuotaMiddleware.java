package github.upinmcse.brewgate.middleware.ratelimiter;

import com.sun.net.httpserver.HttpHandler;
import github.upinmcse.brewgate.config.QuotaConfig;
import github.upinmcse.brewgate.helper.GatewayHelper;
import github.upinmcse.brewgate.middleware.HttpMiddleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.Response;

public class QuotaMiddleware implements HttpMiddleware {
    private static final Logger log = LoggerFactory.getLogger(QuotaMiddleware.class);

    private final QuotaConfig quotaConfig;
    private final RedisClient redisClient;
    private final long periodMillis;
    private final String serviceName;

    public QuotaMiddleware(String serviceName, QuotaConfig quotaConfig, RedisClient redisClient) {
        this.serviceName = serviceName;
        this.quotaConfig = quotaConfig;
        this.redisClient = redisClient;
        this.periodMillis = parseDurationMillis(quotaConfig.getPeriod());
    }

    private static long parseDurationMillis(String period) {
        if (period == null || period.isBlank()) {
            return 60_000L;
        }
        period = period.trim().toLowerCase();
        try {
            if (period.endsWith("ms")) {
                return Long.parseLong(period.replace("ms", ""));
            } else if (period.endsWith("s")) {
                return Long.parseLong(period.replace("s", "")) * 1000L;
            } else if (period.endsWith("m")) {
                return Long.parseLong(period.replace("m", "")) * 60_000L;
            } else if (period.endsWith("h")) {
                return Long.parseLong(period.replace("h", "")) * 3_600_000L;
            } else {
                return Long.parseLong(period) * 1000L;
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid quota period '{}', fallback to 60s", period);
            return 60_000L;
        }
    }

    @Override
    public HttpHandler apply(HttpHandler next) {
        return exchange -> {
            Object userIdObj = exchange.getAttribute("userID");
            if (userIdObj == null) {
                log.error("Quota check failed: User ID not found in context for path '{}'. Quota requires Authentication!",
                        exchange.getRequestURI().getPath());
                GatewayHelper.sendErrorResponse(exchange, 500, "500 Internal Server Error: Quota requires authentication");
                return;
            }

            String userId = userIdObj.toString();
            String redisKey = String.format("quota:%s:%s", serviceName, userId);
            long now = System.currentTimeMillis();
            long minTime = now - periodMillis;
            String member = now + ":" + System.nanoTime();
            long requestCount;

            // Use pipelining to batch all 4 commands into a single network round trip (RTT)
            try (AbstractPipeline pipeline = redisClient.pipelined()) {
                // 1. Remove requests older than the time window [now - period]
                pipeline.zremrangeByScore(redisKey, 0, (double) minTime);
                // 2. Add the current request to the sorted set
                pipeline.zadd(redisKey, (double) now, member);
                // 3. Count the requests remaining within the time window
                Response<Long> countResponse = pipeline.zcard(redisKey);
                // 4. Set a TTL so Redis automatically frees the key if the user stops sending requests
                pipeline.pexpire(redisKey, periodMillis);
                // Flush the pipeline and wait for all responses from Redis
                pipeline.sync();
                requestCount = countResponse.get();
            } catch (Exception e) {
                log.error("Redis error during quota check for user '{}': {}", userId, e.getMessage());
                GatewayHelper.sendErrorResponse(exchange, 500, "500 Internal Server Error: Rate limiter unavailable");
                return;
            }

            if (requestCount > quotaConfig.getLimit()) {
                log.warn("Quota exceeded for user '{}' on '{}': {}/{} requests (Period: {})",
                        userId, serviceName, requestCount, quotaConfig.getLimit(), quotaConfig.getPeriod());
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(periodMillis / 1000));
                GatewayHelper.sendErrorResponse(exchange, 429, "429 Too Many Requests: Quota limit exceeded. Please try again later.");
                return;
            }

            log.debug("User '{}' quota usage on '{}': {}/{}", userId, serviceName, requestCount, quotaConfig.getLimit());
            next.handle(exchange);
        };
    }
}

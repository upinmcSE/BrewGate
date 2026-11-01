package github.upinmcse.brewgate.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import github.upinmcse.brewgate.helper.GatewayHelper;
import github.upinmcse.brewgate.loadbalancer.Backend;
import github.upinmcse.brewgate.loadbalancer.ServerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReverseProxyHandler implements HttpHandler {

    private HttpClient httpClient;
    private ServerPool serverPool;

    private static final Logger log = LoggerFactory.getLogger(ReverseProxyHandler.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length"
    );

    public ReverseProxyHandler(ServerPool serverPool){
        this.serverPool = serverPool;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Backend backend = serverPool.getNextBackend();
        if (backend == null) {
            log.warn("No healthy backends available for path: {}", exchange.getRequestURI().getPath());
            GatewayHelper.sendErrorResponse(exchange, 503, "503 Service Unavailable: No healthy backends");
            return;
        }

        try{
            HttpRequest backendRequest = buildBackendRequest(exchange, backend.getUri());

            HttpResponse<InputStream> backendResponse = httpClient.send(
                    backendRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            writeBackendResponse(backendResponse, exchange);

            log.info("Forwarded {} {} -> {} [{}]",
                    backendRequest.method(), exchange.getRequestURI().getPath(),
                    backend.getUri(), backendResponse.statusCode());

        }catch (Exception e){
            log.error("Backend {} connection failed: {}. Marking as DEAD.", backend.getUri(), e.getMessage());
            backend.setAlive(false);
            GatewayHelper.sendErrorResponse(exchange, 502, "502 Bad Gateway: " + e.getMessage());
        }
    }

    private HttpRequest buildBackendRequest(HttpExchange exchange, URI backendBaseUri) {
        URI requestURI = exchange.getRequestURI();
        String pathAndQuery = requestURI.getRawPath();
        if (requestURI.getRawQuery() != null) {
            pathAndQuery += "?" + requestURI.getRawQuery();
        }
        URI targetUri = URI.create(backendBaseUri + pathAndQuery);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(Duration.ofSeconds(30));

        String method = exchange.getRequestMethod();
        InputStream requestBodyStream = exchange.getRequestBody();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> requestBodyStream));
        }

        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            String headerKey = entry.getKey();
            if (headerKey != null && !HOP_BY_HOP_HEADERS.contains(headerKey.toLowerCase())) {
                for (String headerValue : entry.getValue()) {
                    requestBuilder.header(headerKey, headerValue);
                }
            }
        }

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        requestBuilder.header("X-Forwarded-For", clientIp);

        Object userId = exchange.getAttribute("userID");
        if (userId != null) {
            requestBuilder.header("X-User-ID", userId.toString());
        }
        return requestBuilder.build();
    }

    private void writeBackendResponse(HttpResponse<InputStream> backendResponse, HttpExchange exchange) throws IOException {
        for (Map.Entry<String, List<String>> header : backendResponse.headers().map().entrySet()) {
            String key = header.getKey();
            if (key != null && !HOP_BY_HOP_HEADERS.contains(key.toLowerCase())) {
                for (String value : header.getValue()) {
                    exchange.getResponseHeaders().add(key, value);
                }
            }
        }

        exchange.sendResponseHeaders(backendResponse.statusCode(), 0);

        try (InputStream in = backendResponse.body();
             OutputStream out = exchange.getResponseBody()) {
            in.transferTo(out);
        }
    }

}

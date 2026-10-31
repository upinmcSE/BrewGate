package github.upinmcse.brewgate.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

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
import java.util.logging.Logger;

public class ReverseProxyHandler implements HttpHandler {

    private HttpClient httpClient;
    private String backendUrl;

    private static final Logger LOG = Logger.getLogger(ReverseProxyHandler.class.getName());

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

    public ReverseProxyHandler(String backendUrl){
        this.backendUrl = backendUrl.endsWith("/")
                ? backendUrl.substring(0, backendUrl.length() -1)
                : backendUrl;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try{
            HttpRequest backendRequest = buildBackendRequest(exchange);

            HttpResponse<InputStream> backendResponse = httpClient.send(
                    backendRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            writeBackendResponse(backendResponse, exchange);

            LOG.info(String.format("[Proxy] %s %s -> %s [%d]%n",
                    backendRequest.method(), exchange.getRequestURI().getPath(),
                    backendRequest.uri(), backendResponse.statusCode()));

        }catch (Exception e){
            LOG.warning("[Proxy Error] Cannot forward request: " + e.getMessage());
            sendErrorResponse(exchange, 502, "502 Bad Gateway: " + e.getMessage());
        }
    }

    private HttpRequest buildBackendRequest(HttpExchange exchange) {
        URI requestURI = exchange.getRequestURI();
        String pathAndQuery = requestURI.getRawPath();
        if (requestURI.getRawQuery() != null) {
            pathAndQuery += "?" + requestURI.getRawQuery();
        }
        URI targetUri = URI.create(this.backendUrl + pathAndQuery);

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

    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, message.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(message.getBytes());
        }
    }
}

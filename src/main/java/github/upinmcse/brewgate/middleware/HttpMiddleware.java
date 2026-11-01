package github.upinmcse.brewgate.middleware;

import com.sun.net.httpserver.HttpHandler;

@FunctionalInterface
public interface HttpMiddleware {
    HttpHandler apply(HttpHandler next);
}

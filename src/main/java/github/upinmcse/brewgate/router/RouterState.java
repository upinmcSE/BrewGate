package github.upinmcse.brewgate.router;

import com.sun.net.httpserver.HttpHandler;
import github.upinmcse.brewgate.loadbalancer.ServerPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RouterState implements AutoCloseable{

    private final Map<String, HttpHandler> routes = new HashMap<>();
    private final List<ServerPool> pools = new ArrayList<>();

    public RouterState(){}

    public void addRoute(String pathPrefix, HttpHandler handler, ServerPool serverPool){
        routes.put(pathPrefix, handler);
        if (serverPool != null) {
            pools.add(serverPool);
        }
    }

    /**
     * Finds the handler whose prefix is the longest match for the given request path.
     *
     * <p>Example: with routes "/users/" and "/users/profile/", the request
     * "/users/profile/42" resolves to the "/users/profile/" handler rather
     * than the more generic "/users/" one.
     *
     * @param requestPath the URI path of the incoming request
     * @return the matching handler, or {@code null} if no prefix matches
     */
    public HttpHandler match(String requestPath) {
        String matchedPrefix = null;
        for (String prefix : routes.keySet()) {
            if (requestPath.startsWith(prefix)) {
                if (matchedPrefix == null || prefix.length() > matchedPrefix.length()) {
                    matchedPrefix = prefix;
                }
            }
        }
        return matchedPrefix != null ? routes.get(matchedPrefix) : null;
    }

    @Override
    public void close() {
        for (ServerPool pool : pools) {
            pool.close();
        }
    }
}

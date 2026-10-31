package github.upinmcse.brewgate.loadbalancer;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

public class Backend {
    private final String id;
    private final URI uri;
    private final AtomicBoolean isAlive;

    public Backend(String id, URI uri) {
        this.id = id;
        this.uri = uri;
        this.isAlive = new AtomicBoolean(true);
    }
    public String getId() {
        return id;
    }
    public URI getUri() {
        return uri;
    }
    public boolean isAlive() {
        return isAlive.get();
    }
    public void setAlive(boolean alive) {
        this.isAlive.set(alive);
    }
}

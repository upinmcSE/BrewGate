package github.upinmcse.brewgate.loadbalancer;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

public class ServerPool {

    private static final Logger LOG = Logger.getLogger(ServerPool.class.getName());

    private final Map<String, Backend> backends = new HashMap<>();
    private final List<String> backendIds = new ArrayList<>();
    private final AtomicLong current = new AtomicLong();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public ServerPool() {}

    public void addBackend(String id, String url){
        rwLock.writeLock().lock();
        try{
            if (backends.containsKey(id)) {
                return;
            }
            URI uri = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
            Backend backend = new Backend(id, uri);
            backends.put(id, backend);
            backendIds.add(id);

            LOG.info(String.format("[Pool] Added backend: %s (ID: %s)%n", url, id));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void removeBackend(String id) {
        rwLock.writeLock().lock();
        try {
            Backend b = backends.remove(id);
            backendIds.remove(id);
            if (b != null) {
                LOG.info(String.format("[Pool] Removed backend: %s (ID: %s)%n", b.getUri(), id));
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Backend getNextBackend() {
        rwLock.readLock().lock();
        try {
            int total = backends.size();
            if (total == 0) {
                return null;
            }
            long next = current.incrementAndGet();
            for (int i = 0; i < total; i++) {
                int idx = (int) Math.floorMod(next + i, (long) total); // modulo
                String id = backendIds.get(idx);
                Backend backend = backends.get(id);
                if (backend != null && backend.isAlive()) {
                    return backend;
                }
            }
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }
}

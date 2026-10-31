package github.upinmcse.brewgate.loadbalancer;

import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.HealthClient;
import org.kiwiproject.consul.model.ConsulResponse;
import org.kiwiproject.consul.model.health.Service;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.kiwiproject.consul.option.QueryOptions;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

public class ServerPool implements AutoCloseable{

    private static final Logger LOG = Logger.getLogger(ServerPool.class.getName());

    private final Map<String, Backend> backends = new HashMap<>();
    private final List<String> backendIds = new ArrayList<>();
    private final AtomicLong current = new AtomicLong();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

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

    public void startConsulWatcher(Consul consul, String serviceName) {
        Thread.ofVirtual().name("consul-watcher-" + serviceName).start(() -> {
            HealthClient healthClient = consul.healthClient();
            BigInteger lastIndex = BigInteger.ZERO;

            LOG.info("[Consul Watcher] Started for service: " + serviceName);

            while(isRunning.get() && !Thread.currentThread().isInterrupted()){
                try {
                    QueryOptions queryOptions = QueryOptions.blockSeconds(10, lastIndex).build();

                    ConsulResponse<List<ServiceHealth>> response = healthClient.getHealthyServiceInstances(serviceName, queryOptions);

                    BigInteger newIndex = response.getIndex();
                    if (newIndex.equals(lastIndex)) {
                        continue;
                    }

                    lastIndex = newIndex;
                    List<ServiceHealth> healthyServices = response.getResponse();
                    LOG.info(String.format("[Consul Watcher] Update for '%s'. New index: %s. Found %d healthy instances.%n",
                            serviceName, lastIndex, healthyServices.size()));

                    Set<String> activeIds = new HashSet<>();

                    for (ServiceHealth entry : healthyServices) {
                        Service svc = entry.getService();
                        String serviceId = svc.getId();
                        activeIds.add(serviceId);

                        rwLock.readLock().lock();
                        boolean exists = backends.containsKey(serviceId);
                        Backend existing = backends.get(serviceId);
                        rwLock.readLock().unlock();

                        if (!exists) {
                            String addr = (svc.getAddress() != null && !svc.getAddress().isBlank())
                                    ? svc.getAddress() : entry.getNode().getAddress();
                            String url = "http://" + addr + ":" + svc.getPort();
                            addBackend(serviceId, url);
                        } else if (existing != null) {
                            existing.setAlive(true);
                        }
                    }

                    rwLock.readLock().lock();
                    List<String> toRemove = new ArrayList<>();
                    for (String currentId : backends.keySet()) {
                        if (!activeIds.contains(currentId)) {
                            toRemove.add(currentId);
                        }
                    }
                    rwLock.readLock().unlock();
                    for (String deadId : toRemove) {
                        removeBackend(deadId);
                    }
                } catch (Exception e){
                    if (!isRunning.get()) break;
                    LOG.warning(String.format("[Consul Watcher Error] Service '%s': %s. Retrying in 5s...%n",
                            serviceName, e.getMessage()));
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            LOG.info("[Consul Watcher] Stopped for service: " + serviceName);;
        });
    }

    @Override
    public void close() throws Exception {
        isRunning.set(false);
    }
}

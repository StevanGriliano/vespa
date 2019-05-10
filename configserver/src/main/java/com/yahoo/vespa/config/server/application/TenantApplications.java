// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.application;

import com.yahoo.concurrent.ThreadFactoryFactory;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.log.LogLevel;
import com.yahoo.path.Path;
import com.yahoo.text.Utf8;
import com.yahoo.transaction.Transaction;
import com.yahoo.vespa.config.server.ReloadHandler;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.curator.transaction.CuratorOperation;
import com.yahoo.vespa.curator.transaction.CuratorOperations;
import com.yahoo.vespa.curator.transaction.CuratorTransaction;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * The applications of a tenant, backed by ZooKeeper.
 *
 * Each application is stored under /config/v2/tenants/&lt;tenant&gt;/applications/&lt;application&gt;,
 * the root contains the currently active session, if any, and the /preparing child node contains the
 * session ID of the session to activate next. Locks for synchronising writes to these paths, and changes
 * to the config of this application, are found under /config/v2/tenants/&lt;tenant&gt;/locks/&lt;application&gt;.
 *
 * @author Ulf Lilleengen
 * @author jonmv
 */
public class TenantApplications {

    private static final Logger log = Logger.getLogger(TenantApplications.class.getName());

    private final Curator curator;
    private final Path applicationsPath;
    private final Path locksPath;
    // One thread pool for all instances of this class
    private static final ExecutorService pathChildrenExecutor =
            Executors.newCachedThreadPool(ThreadFactoryFactory.getDaemonThreadFactory(TenantApplications.class.getName()));
    private final Curator.DirectoryCache directoryCache;
    private final ReloadHandler reloadHandler;
    private final Map<ApplicationId, Lock> locks;

    private TenantApplications(Curator curator, ReloadHandler reloadHandler, TenantName tenant) {
        this.curator = curator;
        this.applicationsPath = TenantRepository.getApplicationsPath(tenant);
        this.locksPath = TenantRepository.getLocksPath(tenant);
        this.locks = new ConcurrentHashMap<>(2);
        this.reloadHandler = reloadHandler;
        this.directoryCache = curator.createDirectoryCache(applicationsPath.getAbsolute(), false, false, pathChildrenExecutor);
        this.directoryCache.start();
        this.directoryCache.addListener(this::childEvent);
    }

    public static TenantApplications create(Curator curator, ReloadHandler reloadHandler, TenantName tenant) {
        return new TenantApplications(curator, reloadHandler, tenant);
    }

    /**
     * List the active applications of a tenant in this config server.
     *
     * @return a list of {@link ApplicationId}s that are active.
     */
    public List<ApplicationId> activeApplications() {
        return curator.getChildren(applicationsPath).stream()
                      .sorted()
                      .map(ApplicationId::fromSerializedForm)
                      .filter(id -> activeSessionOf(id).isPresent())
                      .collect(Collectors.toUnmodifiableList());
    }

    public boolean exists(ApplicationId id) {
        return curator.exists(applicationPath(id));
    }

    /** Returns the id of the currently active session for the given application, if any. Throws on unknown applications. */
    private OptionalLong activeSessionOf(ApplicationId id) {
        String data = curator.getData(applicationPath(id)).map(Utf8::toString)
                             .orElseThrow(() -> new IllegalArgumentException("Unknown application '" + id + "'."));
        return data.isEmpty() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(data));
    }

    /**
     * Returns a transaction which writes the given session id as the currently active for the given application,
     * and clears the currently preparing session, as this is now becoming active.
     *
     * @param applicationId An {@link ApplicationId} that represents an active application.
     * @param sessionId Id of the session containing the application package for this id.
     */
    public Transaction createPutTransaction(ApplicationId applicationId, long sessionId) {
        CuratorTransaction transaction = new CuratorTransaction(curator);
        transaction.add(CuratorOperations.setData(applicationPath(applicationId).getAbsolute(), Utf8.toAsciiBytes(sessionId)));
        if (curator.exists(preparingPath(applicationId)))
            transaction.add(CuratorOperations.delete(preparingPath(applicationId).getAbsolute()));
        return transaction;
    }

    /**
     * Creates a node for the given application, marking its existence.
     */
    public void createApplication(ApplicationId id) {
        try (Lock lock = lock(id)) {
            curator.create(applicationPath(id));
        }
    }

    /** Sets the given session id as the one currently being prepared for this application. */
    public void prepare(ApplicationId id, long sessionId) {
        try (Lock lock = lock(id)) {
            if ( ! exists(id))
                throw new IllegalStateException("Can't prepare for '" + id + "'; it doesn't exist");

            curator.set(preparingPath(id), Long.toString(sessionId).getBytes());
        }
    }

    /** Returns the id of the session currently being prepared for this application. */
    public OptionalLong preparing(ApplicationId id) {
        if ( ! exists(id))
            throw new IllegalStateException("Application '" + id + "' can't have a prepared session; it doesn't exist");

        return curator.getData(preparingPath(id)).map(Utf8::toString).map(Long::parseLong)
                      .map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    /**
     * Return the active session id for a given application.
     *
     * @param  applicationId an {@link ApplicationId}
     * @return session id of given application id.
     * @throws IllegalArgumentException if the application does not exist
     */
    public long requireActiveSessionOf(ApplicationId applicationId) {
        return activeSessionOf(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application '" + applicationId + "' has no active session."));
    }

    /**
     * Returns a transaction which deletes this application.
     */
    public CuratorTransaction createDeleteTransaction(ApplicationId applicationId) {
        return CuratorTransaction.from(CuratorOperations.deleteAll(applicationPath(applicationId).getAbsolute(), curator), curator);
    }

    /**
     * Removes all applications not known to this from the config server state.
     */
    public void removeUnusedApplications() {
        reloadHandler.removeApplicationsExcept(Set.copyOf(activeApplications()));
    }

    /**
     * Closes the application repo. Once a repo has been closed, it should not be used again.
     */
    public void close() {
        directoryCache.close();
    }

    /** Returns the lock for changing the session status of the given application. */
    public Lock lock(ApplicationId id) {
        curator.create(lockPath(id));
        Lock lock = locks.computeIfAbsent(id, __ -> new Lock(lockPath(id).getAbsolute(), curator));
        lock.acquire(Duration.ofMinutes(1)); // These locks shouldn't be held for very long.
        return lock;
    }

    private void childEvent(CuratorFramework client, PathChildrenCacheEvent event) {
        switch (event.getType()) {
            case CHILD_ADDED:
                applicationAdded(ApplicationId.fromSerializedForm(Path.fromString(event.getData().getPath()).getName()));
                break;
            // Event CHILD_REMOVED will be triggered on all config servers if deleteApplication() above is called on one of them
            case CHILD_REMOVED:
                applicationRemoved(ApplicationId.fromSerializedForm(Path.fromString(event.getData().getPath()).getName()));
                break;
            case CHILD_UPDATED:
                // do nothing, application just got redeployed
                break;
            default:
                break;
        }
        // We may have lost events and may need to remove applications.
        // New applications are added when session is added, not here. See RemoteSessionRepo.
        removeUnusedApplications();
    }

    private void applicationRemoved(ApplicationId applicationId) {
        reloadHandler.removeApplication(applicationId);
        log.log(LogLevel.INFO, TenantRepository.logPre(applicationId) + "Application removed: " + applicationId);
    }

    private void applicationAdded(ApplicationId applicationId) {
        log.log(LogLevel.DEBUG, TenantRepository.logPre(applicationId) + "Application added: " + applicationId);
    }

    private Path applicationPath(ApplicationId id) {
        return applicationsPath.append(id.serializedForm());
    }

    private Path preparingPath(ApplicationId id) {
        return applicationPath(id).append("preparing");
    }

    private Path lockPath(ApplicationId id) {
        return locksPath.append(id.serializedForm());
    }

}

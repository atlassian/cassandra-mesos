package io.mesosphere.mesos.frameworks.cassandra.executor;

import io.mesosphere.mesos.frameworks.cassandra.executor.jmx.JmxConnect;
import org.apache.cassandra.service.StorageServiceMBean;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class BackupManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupManager.class);

    @NotNull
    private final JmxConnect jmxConnect;

    @NotNull
    private final String backupDir;

    public BackupManager(@NotNull final JmxConnect jmxConnect, @NotNull final String backupDir) {
        this.jmxConnect = jmxConnect;
        this.backupDir = backupDir;
    }

    public void backup(@NotNull final String keyspace) throws IOException {
        backup(keyspace, "snapshot-" + System.currentTimeMillis());
    }

    public void backup(@NotNull final String keyspace, @NotNull final String snapshot) throws IOException {
        final StorageServiceMBean storageServiceProxy = jmxConnect.getStorageServiceProxy();

        LOGGER.info("Creating snapshot of keyspace {}", keyspace);
        storageServiceProxy.takeSnapshot(snapshot, keyspace);

        LOGGER.info("Copying backup of keyspace {}", keyspace);
        copyKeyspaceSnapshot(snapshot, keyspace);

        LOGGER.info("Clearing snapshot of keyspace {}", keyspace);
        storageServiceProxy.clearSnapshot(snapshot, keyspace);
    }

    private void copyKeyspaceSnapshot(@NotNull final String snapshot, @NotNull final String keyspace) throws IOException {
        final List<String> tables = jmxConnect.getColumnFamilyNames(keyspace);
        for (final String table : tables)
            copyTableSnapshot(snapshot, keyspace, table);
    }

    void copyTableSnapshot(@NotNull final String snapshot, @NotNull final String keyspace, @NotNull final String table) throws IOException {
        final File srcDir = findTableSnapshotDir(keyspace, table, snapshot);
        final File destDir = new File(backupDir, keyspace + "/" + table);
        Files.createDirectories(destDir.toPath());

        final File[] files = srcDir.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.isFile()) {
                    Files.copy(file.toPath(), new File(destDir, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    File findTableSnapshotDir(@NotNull final String keyspace, @NotNull final String table, @NotNull final String snapshot) {
        final File dataDir = new File(jmxConnect.getStorageServiceProxy().getAllDataFileLocations()[0]);
        final File keyspaceDir = new File(dataDir, keyspace);

        final File tableDir = findTableDir(keyspaceDir, table);
        final File snapshotDir = new File(tableDir, "/snapshots/" + snapshot);
        if (!snapshotDir.exists()) throw new IllegalStateException("Snapshot dir does not exist: " + snapshotDir);

        return snapshotDir;
    }

    File findTableDir(@NotNull final File keyspaceDir, @NotNull final String table) {
        final File[] files = keyspaceDir.listFiles();
        if (files != null)
            for (final File file : files) {
                if (file.isDirectory() && file.getName().startsWith(table + "-")) {
                    return file;
                }
            }

        throw new IllegalStateException("Failed to found table dir for table " + table + " in keyspace " + keyspaceDir);
    }

    public void restore(@NotNull final String keyspace) throws IOException, TimeoutException {
        final List<String> tables = jmxConnect.getColumnFamilyNames(keyspace);

        for (final String table : tables) {
            LOGGER.info("Restoring backup of {}/{}", keyspace, table);
            restoreTableSnapshot(keyspace, table);

            LOGGER.info("Reloading SSTables for {}/{}", keyspace, table);
            jmxConnect.getColumnFamilyStoreProxy(keyspace, table).loadNewSSTables();
        }
    }

    void restoreTableSnapshot(@NotNull final String keyspace, @NotNull final String table) throws IOException {
        final File dataDir = new File(jmxConnect.getStorageServiceProxy().getAllDataFileLocations()[0]);
        final File keyspaceDir = new File(dataDir, keyspace);

        final File srcDir = new File(backupDir, keyspace + "/" + table);
        final File destDir = findTableDir(keyspaceDir, table);
        Files.createDirectories(destDir.toPath());

        final File[] files = srcDir.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.isFile()) {
                    Files.copy(file.toPath(), new File(destDir, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

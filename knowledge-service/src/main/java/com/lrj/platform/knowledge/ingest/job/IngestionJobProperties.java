package com.lrj.platform.knowledge.ingest.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Durable ingestion job 存储与 reconcile 配置。 */
@ConfigurationProperties(prefix = "app.rag.ingestion")
public class IngestionJobProperties {

    private String store = "memory";
    private Duration processingTimeout = Duration.ofMinutes(15);
    private int reconcileBatchSize = 100;
    private int workerBatchSize = 10;
    private final VersionGc versionGc = new VersionGc();
    private final Datasource datasource = new Datasource();

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public Duration getProcessingTimeout() {
        return processingTimeout;
    }

    public void setProcessingTimeout(Duration processingTimeout) {
        this.processingTimeout = processingTimeout;
    }

    public int getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public int getWorkerBatchSize() {
        return workerBatchSize;
    }

    public void setWorkerBatchSize(int workerBatchSize) {
        this.workerBatchSize = workerBatchSize;
    }

    public void setReconcileBatchSize(int reconcileBatchSize) {
        this.reconcileBatchSize = reconcileBatchSize;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public VersionGc getVersionGc() {
        return versionGc;
    }

    public static class VersionGc {
        private boolean enabled = false;
        private int retainVersions = 2;
        private Duration gracePeriod = Duration.ofDays(7);
        private Duration pollInterval = Duration.ofHours(1);
        private int batchSize = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRetainVersions() { return retainVersions; }
        public void setRetainVersions(int retainVersions) { this.retainVersions = retainVersions; }
        public Duration getGracePeriod() { return gracePeriod; }
        public void setGracePeriod(Duration gracePeriod) { this.gracePeriod = gracePeriod; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class Datasource {

        private String url = "jdbc:mysql://localhost:3306/knowledge_ingestion"
                + "?useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        private String username = "knowledge_ingestion_app";
        private String password = "knowledge-ingestion-app-dev";
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private int maximumPoolSize = 8;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }
    }
}

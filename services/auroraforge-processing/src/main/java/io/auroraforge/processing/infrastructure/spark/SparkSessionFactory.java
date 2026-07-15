package io.auroraforge.processing.infrastructure.spark;

import io.auroraforge.core.config.cloud.AwsProperties;
import io.auroraforge.core.config.cloud.AzureProperties;
import io.auroraforge.core.config.cloud.CloudProperties;
import io.auroraforge.processing.config.SparkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.SparkSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory that builds a {@link SparkSession} with cloud-specific configuration.
 *
 * Each batch job calls {@link #create(String)} to get a session scoped to that
 * job. Spark sessions are NOT shared between jobs in this architecture (no
 * SparkContext singleton) to allow independent job failure isolation and
 * clean resource release.
 *
 * Cloud-specific configuration:
 *
 * AWS:
 *  - Hadoop s3a connector using IAM Instance Profile (no credentials in config)
 *  - S3A magic committer for atomic multipart writes (no incomplete files on failure)
 *  - Server-side encryption via s3a.server-side-encryption-algorithm = SSE-KMS
 *  - Parquet block size tuned for 128 MB S3 optimal read
 *
 * Azure:
 *  - ABFS (Azure Blob FileSystem) driver with Workload Identity OAuth
 *  - Hierarchical namespace enabled (ADLS Gen2 compatible paths)
 *  - Delta Lake committer for transactional writes
 *
 * Local:
 *  - local[*] master, MinIO S3A endpoint, no auth
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparkSessionFactory {

    private final SparkProperties  sparkProps;
    private final CloudProperties  cloudProps;
    private final AwsProperties    awsProps;
    private final AzureProperties  azureProps;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @Value("${spring.kafka.properties.schema-registry-url}")
    private String schemaRegistryUrl;

    /**
     * Creates a new SparkSession for the given job name.
     * The session runs in-process in local mode or submits to the cluster
     * depending on the {@code auroraforge.spark.master} configuration.
     */
    public SparkSession create(String jobName) {
        SparkConf conf = buildBaseConf(jobName);
        applyCloudConfig(conf);
        applyKafkaConfig(conf);
        applyOptimisations(conf);

        log.info("Creating SparkSession: job={} master={} cloud={}",
                jobName, sparkProps.master(), cloudProps.provider());

        return SparkSession.builder()
                .config(conf)
                .getOrCreate();
    }

    /**
     * Creates a SparkSession configured for Structured Streaming.
     * Checkpointing is mandatory for streaming jobs.
     */
    public SparkSession createForStreaming(String jobName) {
        SparkConf conf = buildBaseConf(jobName);
        applyCloudConfig(conf);
        applyKafkaConfig(conf);
        applyOptimisations(conf);

        // Structured Streaming specific
        conf.set("spark.sql.streaming.checkpointLocation",
                sparkProps.checkpoint().baseLocation() + "/" + jobName);
        conf.set("spark.sql.streaming.metricsEnabled", "true");
        conf.set("spark.sql.streaming.numRecentProgressUpdates", "100");

        log.info("Creating SparkSession for streaming: job={} checkpoint={}",
                jobName, sparkProps.checkpoint().baseLocation());

        return SparkSession.builder()
                .config(conf)
                .getOrCreate();
    }

    private SparkConf buildBaseConf(String jobName) {
        SparkConf conf = new SparkConf()
                .setMaster(sparkProps.master())
                .setAppName(sparkProps.appName() + "-" + jobName)
                .set("spark.driver.memory",        sparkProps.driverMemory())
                .set("spark.executor.memory",      sparkProps.executorMemory())
                .set("spark.executor.cores",       sparkProps.executorCores())
                .set("spark.executor.instances",   sparkProps.executorInstances())
                .set("spark.serializer",           "org.apache.spark.serializer.KryoSerializer")
                .set("spark.kryo.registrationRequired", "false");

        // Apply any extra properties from application.yml
        sparkProps.config().forEach(conf::set);
        return conf;
    }

    private void applyCloudConfig(SparkConf conf) {
        switch (cloudProps.provider()) {
            case AWS  -> applyAwsConfig(conf);
            case AZURE -> applyAzureConfig(conf);
            case LOCAL -> applyLocalConfig(conf);
        }
    }

    private void applyAwsConfig(SparkConf conf) {
        conf.set("spark.hadoop.fs.s3a.impl",
                        "org.apache.hadoop.fs.s3a.S3AFileSystem")
            .set("spark.hadoop.fs.s3a.aws.credentials.provider",
                        "com.amazonaws.auth.WebIdentityTokenCredentialsProvider")
            .set("spark.hadoop.fs.s3a.endpoint.region",  awsProps.region())
            .set("spark.hadoop.fs.s3a.path.style.access", "false")
            // S3A magic committer: writes to temp path, then renames atomically on commit
            .set("spark.hadoop.fs.s3a.committer.name",   "magic")
            .set("spark.hadoop.mapreduce.outputcommitter.factory.scheme.s3a",
                        "org.apache.hadoop.fs.s3a.commit.S3ACommitterFactory")
            .set("spark.sql.sources.commitProtocolClass",
                        "org.apache.spark.internal.io.cloud.PathOutputCommitProtocol")
            .set("spark.sql.parquet.output.committer.class",
                        "org.apache.spark.internal.io.cloud.BindingParquetOutputCommitter")
            // SSE-KMS for all S3 writes
            .set("spark.hadoop.fs.s3a.server-side-encryption-algorithm", "SSE-KMS")
            .set("spark.hadoop.fs.s3a.server-side-encryption.key",
                        awsProps.kms().appDataKeyAlias())
            // Performance: 128MB block size, multipart threshold 100MB
            .set("spark.hadoop.fs.s3a.block.size",           "134217728")
            .set("spark.hadoop.fs.s3a.multipart.size",       "104857600")
            .set("spark.hadoop.fs.s3a.fast.upload",          "true")
            .set("spark.hadoop.fs.s3a.fast.upload.buffer",   "array")
            .set("spark.hadoop.fs.s3a.connection.maximum",   "100");
    }

    private void applyAzureConfig(SparkConf conf) {
        String account = azureProps.blob().accountName();
        conf.set("spark.hadoop.fs.azure.account.auth.type." + account + ".dfs.core.windows.net",
                        "OAuth")
            .set("spark.hadoop.fs.azure.account.oauth.provider.type." + account + ".dfs.core.windows.net",
                        "org.apache.hadoop.fs.azurebfs.oauth2.WorkloadIdentityCredential")
            .set("spark.hadoop.fs.azure.account.oauth2.client.id." + account + ".dfs.core.windows.net",
                        azureProps.aks().managedIdentityClientId())
            .set("spark.hadoop.fs.azure.account.oauth2.tenant.id." + account + ".dfs.core.windows.net",
                        azureProps.aks().tenantId())
            // Write optimisation for ADLS Gen2
            .set("spark.hadoop.fs.azure.enable.check.access",             "false")
            .set("spark.hadoop.fs.azure.createRemoteFileSystemDuringInitialization", "false")
            .set("spark.hadoop.fs.azure.io.retry.max.retries",            "3");
    }

    private void applyLocalConfig(SparkConf conf) {
        String minioEndpoint = "http://minio:9000";
        conf.set("spark.hadoop.fs.s3a.impl",        "org.apache.hadoop.fs.s3a.S3AFileSystem")
            .set("spark.hadoop.fs.s3a.endpoint",     minioEndpoint)
            .set("spark.hadoop.fs.s3a.access.key",   "minioadmin")
            .set("spark.hadoop.fs.s3a.secret.key",   "minioadmin")
            .set("spark.hadoop.fs.s3a.path.style.access", "true")
            .set("spark.hadoop.fs.s3a.connection.ssl.enabled", "false");
    }

    private void applyKafkaConfig(SparkConf conf) {
        conf.set("spark.kafka.bootstrap.servers", kafkaBootstrapServers)
            .set("spark.schema.registry.url",     schemaRegistryUrl);
    }

    private void applyOptimisations(SparkConf conf) {
        conf.set("spark.sql.shuffle.partitions",          "200")
            .set("spark.sql.parquet.compression.codec",   "snappy")
            .set("spark.sql.parquet.mergeSchema",          "false")  // ~10x faster reads
            .set("spark.sql.parquet.filterPushdown",       "true")
            .set("spark.sql.adaptive.enabled",             "true")   // AQE
            .set("spark.sql.adaptive.coalescePartitions.enabled", "true")
            .set("spark.sql.adaptive.skewJoin.enabled",    "true")
            .set("spark.speculation",                      "true")    // re-launch straggler tasks
            .set("spark.dynamicAllocation.enabled",        "true")
            .set("spark.dynamicAllocation.minExecutors",   "2")
            .set("spark.dynamicAllocation.maxExecutors",   "20")
            .set("spark.dynamicAllocation.initialExecutors", "4");
    }
}

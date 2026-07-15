package io.auroraforge.core.config.cloud;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Top-level cloud provider selection and multi-cloud behaviour.
 *
 * Bound from: auroraforge.cloud.*
 *
 * The {@code provider} property selects which cloud adapter beans are activated
 * via {@code @ConditionalOnProperty}. {@code failoverPolicy} controls whether
 * the platform automatically reroutes traffic on cloud-partition events or
 * requires a manual operator action (safe for regulated workloads).
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.cloud")
public record CloudProperties(

        @NotNull
        CloudProviderType provider,

        boolean enableMultiCloud,

        boolean enableCrossCloudSync,

        FailoverPolicy failoverPolicy,

        String primaryRegion,

        String secondaryRegion

) {
    public CloudProperties {
        if (failoverPolicy == null) failoverPolicy = FailoverPolicy.AUTOMATIC;
        if (primaryRegion  == null) primaryRegion  = "us-east-1";
    }

    public enum FailoverPolicy { AUTOMATIC, MANUAL }

    public enum CloudProviderType { AWS, AZURE, LOCAL }
}

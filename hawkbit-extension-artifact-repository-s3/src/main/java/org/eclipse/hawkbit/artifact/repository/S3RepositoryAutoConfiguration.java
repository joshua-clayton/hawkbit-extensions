/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.artifact.repository;

import java.net.URI;

import org.eclipse.hawkbit.artifact.ArtifactStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * The Spring auto-configuration to register the necessary beans for the S3
 * artifact storage implementation.
 */
@Configuration
@ConditionalOnProperty(prefix = "org.eclipse.hawkbit.artifact.repository.s3", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(S3RepositoryProperties.class)
public class S3RepositoryAutoConfiguration {

    @Value("${aws.region:#{null}}")
    private String region;

    @Value("${aws.s3.endpoint:#{null}}")
    private String endpoint;

    /**
     * The {@link DefaultCredentialsProvider} resolves credentials from the
     * standard AWS SDK v2 chain: environment variables, system properties,
     * web identity tokens, container credentials (including EKS Pod
     * Identity), and instance profile credentials, in that order.
     *
     * @return the {@link DefaultCredentialsProvider} if no other
     *         {@link AwsCredentialsProvider} bean is registered.
     */
    @Bean
    @ConditionalOnMissingBean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    /**
     * The default S3 client override configuration, which declares the
     * configuration for managing connection behavior to s3.
     *
     * @return the default {@link ClientOverrideConfiguration} bean with the
     *         default client configuration
     */
    @Bean
    @ConditionalOnMissingBean
    public ClientOverrideConfiguration clientOverrideConfiguration() {
        return ClientOverrideConfiguration.builder().build();
    }

    /**
     * @return the {@link S3Client} if no other bean is registered.
     */
    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client() {
        final S3ClientBuilder s3ClientBuilder = S3Client.builder()
                .credentialsProvider(awsCredentialsProvider())
                .overrideConfiguration(clientOverrideConfiguration());
        if (StringUtils.hasLength(endpoint)) {
            s3ClientBuilder.endpointOverride(URI.create(endpoint));
        }
        if (StringUtils.hasLength(region)) {
            s3ClientBuilder.region(Region.of(region));
        }
        return s3ClientBuilder.build();
    }

    /**
     * @return AWS S3 artifact storage implementation.
     */
    @Bean
    @ConditionalOnMissingBean
    public ArtifactStorage artifactStorage(final S3RepositoryProperties s3Properties) {
        return new S3Repository(s3Client(), s3Properties);
    }
}

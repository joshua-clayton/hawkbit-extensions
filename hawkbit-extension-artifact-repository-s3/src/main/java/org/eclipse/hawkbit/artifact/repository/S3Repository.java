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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.eclipse.hawkbit.artifact.AbstractArtifactStorage;
import org.eclipse.hawkbit.artifact.ArtifactStorage;
import org.eclipse.hawkbit.artifact.exception.ArtifactBinaryNotFoundException;
import org.eclipse.hawkbit.artifact.exception.ArtifactStoreException;
import org.eclipse.hawkbit.artifact.model.ArtifactHashes;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * An {@link ArtifactStorage} implementation which stores artifacts in Amazon S3.
 * All binaries are stored in single bucket using the configured name
 * {@link S3RepositoryProperties#getBucketName()}.
 */
@Validated
public class S3Repository extends AbstractArtifactStorage {

    private final S3Client s3Client;
    private final S3RepositoryProperties s3Properties;

    public S3Repository(final S3Client s3Client, final S3RepositoryProperties s3Properties) {
        Assert.notNull(s3Client, "s3Client cannot be null");
        Assert.notNull(s3Properties, "s3Properties cannot be null");
        this.s3Client = s3Client;
        this.s3Properties = s3Properties;
    }

    @Override
    public void deleteBySha1(final String tenant, final String sha1) {
        try {
            final String key = buildKey(tenant, sha1);
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Properties.getBucketName())
                    .key(key)
                    .build());
        } catch (final S3Exception e) {
            throw new ArtifactStoreException("Failed to delete artifact from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getBySha1(final String tenant, final String sha1) {
        try {
            final String key = buildKey(tenant, sha1);
            if (!existsBySha1(tenant, sha1)) {
                throw new ArtifactBinaryNotFoundException(sha1);
            }
            final InputStream objectContent = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(s3Properties.getBucketName())
                    .key(key)
                    .build());
            return new BufferedInputStream(objectContent);
        } catch (final S3Exception e) {
            throw new ArtifactBinaryNotFoundException(sha1);
        }
    }

    @Override
    public void deleteByTenant(final String tenant) {
        try {
            final String prefix = tenant + "/";
            s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(s3Properties.getBucketName())
                            .prefix(prefix)
                            .build())
                    .contents()
                    .forEach(s3Object -> s3Client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(s3Properties.getBucketName())
                            .key(s3Object.key())
                            .build()));
        } catch (final S3Exception e) {
            throw new ArtifactStoreException("Failed to delete tenant artifacts from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean existsBySha1(final String tenant, final String sha1) {
        try {
            final String key = buildKey(tenant, sha1);
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(s3Properties.getBucketName())
                    .key(key)
                    .build());
            return true;
        } catch (final S3Exception e) {
            return false;
        }
    }

    @Override
    protected void store(final String tenant, final ArtifactHashes base16Hashes, final String contentType, final File tempFile)
            throws IOException {
        try {
            final String key = buildKey(tenant, base16Hashes.sha1());
            if (!existsBySha1(tenant, base16Hashes.sha1())) {
                final PutObjectRequest.Builder putRequestBuilder = PutObjectRequest.builder()
                        .bucket(s3Properties.getBucketName())
                        .key(key);
                if (contentType != null) {
                    putRequestBuilder.contentType(contentType);
                }
                s3Client.putObject(putRequestBuilder.build(), RequestBody.fromFile(tempFile));
            }
        } catch (final S3Exception e) {
            throw new ArtifactStoreException("Failed to store artifact in S3: " + e.getMessage(), e);
        }
    }

    private String buildKey(final String tenant, final String sha1) {
        return tenant + "/" + sha1;
    }
}

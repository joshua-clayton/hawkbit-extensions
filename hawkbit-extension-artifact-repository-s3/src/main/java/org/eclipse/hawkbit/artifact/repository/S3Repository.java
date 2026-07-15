/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others
 * Patched for hawkbit 0.10.0 compatibility
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

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

/**
 * An {@link ArtifactStorage} implementation which stores artifacts in Amazon S3.
 * All binaries are stored in single bucket using the configured name
 * {@link S3RepositoryProperties#getBucketName()}.
 */
@Validated
public class S3Repository extends AbstractArtifactStorage {

    private final AmazonS3 amazonS3;
    private final S3RepositoryProperties s3Properties;

    public S3Repository(final AmazonS3 amazonS3, final S3RepositoryProperties s3Properties) {
        Assert.notNull(amazonS3, "amazonS3 cannot be null");
        Assert.notNull(s3Properties, "s3Properties cannot be null");
        this.amazonS3 = amazonS3;
        this.s3Properties = s3Properties;
    }

    @Override
    public void deleteBySha1(final String tenant, final String sha1) {
        try {
            final String key = buildKey(tenant, sha1);
            amazonS3.deleteObject(s3Properties.getBucketName(), key);
        } catch (final AmazonS3Exception e) {
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
            final S3Object s3Object = amazonS3.getObject(new GetObjectRequest(s3Properties.getBucketName(), key));
            return new BufferedInputStream(s3Object.getObjectContent());
        } catch (final AmazonS3Exception e) {
            throw new ArtifactBinaryNotFoundException(sha1);
        }
    }

    @Override
    public void deleteByTenant(final String tenant) {
        try {
            final String prefix = tenant + "/";
            amazonS3.listObjects(s3Properties.getBucketName(), prefix).getObjectSummaries().stream()
                    .forEach(summary -> amazonS3.deleteObject(s3Properties.getBucketName(), summary.getKey()));
        } catch (final AmazonS3Exception e) {
            throw new ArtifactStoreException("Failed to delete tenant artifacts from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean existsBySha1(final String tenant, final String sha1) {
        try {
            final String key = buildKey(tenant, sha1);
            return amazonS3.doesObjectExist(s3Properties.getBucketName(), key);
        } catch (final AmazonS3Exception e) {
            return false;
        }
    }

    @Override
    protected void store(final String tenant, final ArtifactHashes base16Hashes, final String contentType, final File tempFile)
            throws IOException {
        try {
            final String key = buildKey(tenant, base16Hashes.sha1());
            if (!existsBySha1(tenant, base16Hashes.sha1())) {
                final PutObjectRequest putRequest = new PutObjectRequest(s3Properties.getBucketName(), key, tempFile);
                if (contentType != null) {
                    putRequest.getMetadata().setContentType(contentType);
                }
                amazonS3.putObject(putRequest);
            }
        } catch (final AmazonS3Exception e) {
            throw new ArtifactStoreException("Failed to store artifact in S3: " + e.getMessage(), e);
        }
    }

    private String buildKey(final String tenant, final String sha1) {
        return tenant + "/" + sha1;
    }
}

/**
 * Copyright (c) 2019 Rico Pahlisch and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.artifact.repository;

import static java.nio.channels.Channels.newInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.eclipse.hawkbit.artifact.AbstractArtifactStorage;
import org.eclipse.hawkbit.artifact.ArtifactStorage;
import org.eclipse.hawkbit.artifact.exception.ArtifactBinaryNotFoundException;
import org.eclipse.hawkbit.artifact.model.ArtifactHashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;

/**
 * An {@link ArtifactStorage} implementation for the Gcloud GCS service. All
 * binaries are stored in single bucket using the configured name
 * {@link GcsRepositoryProperties#getBucketName()}.
 */
@Validated
public class GcsRepository extends AbstractArtifactStorage {
    private static final Logger LOG = LoggerFactory.getLogger(GcsRepository.class);

    private final Storage gcsStorage;
    private final GcsRepositoryProperties gcsProperties;

    /**
     * Constructor.
     *
     * @param gcsStorage
     *            the gcsStorage client to use
     * @param gcsProperties
     *            the properties which e.g. holds the name of the bucket to
     *            store in
     */
    public GcsRepository(final Storage gcsStorage, final GcsRepositoryProperties gcsProperties) {
        Assert.notNull(gcsStorage, "gcsStorage cannot be null");
        Assert.notNull(gcsProperties, "gcsProperties cannot be null");
        this.gcsStorage = gcsStorage;
        this.gcsProperties = gcsProperties;
    }

    private static String objectKey(final String tenant, final String sha1Hash) {
        return sanitizeTenant(tenant) + "/" + sha1Hash;
    }

    @Override
    protected void store(final String tenant, final ArtifactHashes base16Hashes, final String contentType,
            final File tempFile) throws IOException {
        final String key = objectKey(tenant, base16Hashes.sha1());

        LOG.info("Storing file {} with length {} to GCS bucket {} with key {}", tempFile.getName(), tempFile.length(),
                gcsProperties.getBucketName(), key);

        if (existsBySha1(tenant, base16Hashes.sha1())) {
            LOG.debug("Artifact {} already exists on GCS bucket {}, don't need to upload twice", key,
                    gcsProperties.getBucketName());
            return;
        }

        try (InputStream fileStream = new FileInputStream(tempFile)) {
            final byte[] data = IOUtils.toByteArray(fileStream);
            final BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(gcsProperties.getBucketName(), key))
                    .setMd5(base16Hashes.md5()).setContentType(contentType).build();
            final Blob blob = gcsStorage.create(blobInfo, data);
            LOG.debug("Artifact {} stored on GCS bucket {} with server side Etag {} and MD5 hash {}", key,
                    gcsProperties.getBucketName(), blob.getEtag(), blob.getMd5());
        }
    }

    @Override
    public void deleteBySha1(final String tenant, final String sha1Hash) {
        final String key = objectKey(tenant, sha1Hash);
        LOG.info("Deleting GCS object from bucket {} and key {}", gcsProperties.getBucketName(), key);
        gcsStorage.delete(BlobId.of(gcsProperties.getBucketName(), key));
    }

    @Override
    public InputStream getBySha1(final String tenant, final String sha1Hash) {
        final String key = objectKey(tenant, sha1Hash);

        LOG.info("Retrieving GCS object from bucket {} and key {}", gcsProperties.getBucketName(), key);
        final Blob blob = gcsStorage.get(gcsProperties.getBucketName(), key);
        if (blob == null || !blob.exists()) {
            throw new ArtifactBinaryNotFoundException(sha1Hash);
        }
        return newInputStream(gcsStorage.reader(BlobId.of(gcsProperties.getBucketName(), key)));
    }

    @Override
    public void deleteByTenant(final String tenant) {
        final String folder = sanitizeTenant(tenant);

        LOG.info("Deleting GCS object folder (tenant) from bucket {} and key {}", gcsProperties.getBucketName(),
                folder);
        final Page<Blob> blobs = gcsStorage.list(gcsProperties.getBucketName(), Storage.BlobListOption.currentDirectory(),
                Storage.BlobListOption.prefix(folder + "/"));
        for (final Blob blob : blobs.iterateAll()) {
            gcsStorage.delete(blob.getBlobId());
        }
    }

    @Override
    public boolean existsBySha1(final String tenant, final String sha1Hash) {
        final Blob blob = gcsStorage.get(gcsProperties.getBucketName(), objectKey(tenant, sha1Hash));
        return blob != null && blob.exists();
    }
}

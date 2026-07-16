/**
 * Copyright (c) 2018 Microsoft and others
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
import java.net.URISyntaxException;

import org.eclipse.hawkbit.artifact.AbstractArtifactStorage;
import org.eclipse.hawkbit.artifact.ArtifactStorage;
import org.eclipse.hawkbit.artifact.exception.ArtifactBinaryNotFoundException;
import org.eclipse.hawkbit.artifact.exception.ArtifactStoreException;
import org.eclipse.hawkbit.artifact.model.ArtifactHashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.OperationContext;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobContainerPublicAccessType;
import com.microsoft.azure.storage.blob.BlobRequestOptions;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

/**
 * An {@link ArtifactStorage} implementation for Azure Storage.
 */
@Validated
public class AzureStorageRepository extends AbstractArtifactStorage {

    private static final Logger LOG = LoggerFactory.getLogger(AzureStorageRepository.class);
    private static final Logger AZURE_SDK_LOG = LoggerFactory.getLogger(CloudBlockBlob.class);

    private final CloudBlobClient blobClient;
    private final AzureStorageRepositoryProperties properties;

    public AzureStorageRepository(final CloudStorageAccount storageAccount,
            final AzureStorageRepositoryProperties properties) {
        Assert.notNull(storageAccount, "storageAccount cannot be null");
        Assert.notNull(properties, "properties cannot be null");
        this.blobClient = storageAccount.createCloudBlobClient();
        this.properties = properties;
    }

    private CloudBlobContainer getContainer() throws URISyntaxException, StorageException {
        final CloudBlobContainer container = blobClient.getContainerReference(properties.getContainerName());
        container.createIfNotExists(BlobContainerPublicAccessType.CONTAINER, new BlobRequestOptions(),
                new OperationContext());
        return container;
    }

    @Override
    protected void store(final String tenant, final ArtifactHashes base16Hashes, final String contentType,
            final File tempFile) throws IOException {
        try {
            final CloudBlockBlob blob = getBlob(tenant, base16Hashes.sha1());

            LOG.info("Storing file {} with length {} to Azure Storage container {} in directory {}",
                    tempFile.getName(), tempFile.length(), properties.getContainerName(), blob.getParent());

            if (blob.exists()) {
                LOG.debug(
                        "Artifact {} for tenant {} already exists on Azure Storage container {}, don't need to upload twice",
                        base16Hashes.sha1(), tenant, properties.getContainerName());
                return;
            }

            if (contentType != null) {
                blob.getProperties().setContentType(contentType);
            }

            final OperationContext context = new OperationContext();
            context.setLoggingEnabled(true);
            context.setLogger(AZURE_SDK_LOG);

            final BlobRequestOptions options = new BlobRequestOptions();
            options.setConcurrentRequestCount(properties.getConcurrentRequestCount());

            blob.uploadFromFile(tempFile.getAbsolutePath(), null, options, context);

            LOG.debug("Artifact {} stored on Azure Storage container {} with server side Etag {}",
                    base16Hashes.sha1(), blob.getContainer().getName(), blob.getProperties().getEtag());
        } catch (final URISyntaxException | StorageException e) {
            throw new ArtifactStoreException("Failed to store artifact into Azure storage", e);
        }
    }

    @Override
    public void deleteBySha1(final String tenant, final String sha1Hash16) {
        try {
            final CloudBlockBlob blob = getBlob(tenant, sha1Hash16);

            LOG.info("Deleting Azure Storage blob from container {} and hash {} for tenant {}",
                    blob.getContainer().getName(), sha1Hash16, tenant);
            blob.delete();

        } catch (final URISyntaxException | StorageException e) {
            throw new ArtifactStoreException("Failed to delete artifact from Azure storage", e);
        }
    }

    @Override
    public InputStream getBySha1(final String tenant, final String sha1Hash16) {
        try {
            final CloudBlockBlob blob = getBlob(tenant, sha1Hash16);
            if (!blob.exists()) {
                throw new ArtifactBinaryNotFoundException(sha1Hash16);
            }

            LOG.info("Loading Azure Storage blob from container {} and hash {} for tenant {}",
                    blob.getContainer().getName(), sha1Hash16, tenant);
            return new BufferedInputStream(blob.openInputStream());
        } catch (final ArtifactBinaryNotFoundException e) {
            throw e;
        } catch (final URISyntaxException | StorageException e) {
            throw new ArtifactStoreException("Failed to load artifact from Azure storage", e);
        }
    }

    @Override
    public void deleteByTenant(final String tenant) {

        try {
            final CloudBlobContainer container = getContainer();
            final CloudBlobDirectory tenantDirectory = container.getDirectoryReference(sanitizeTenant(tenant));

            LOG.info("Deleting Azure Storage blob folder (tenant) from container {} for tenant {}", container.getName(),
                    tenant);

            for (final ListBlobItem blobItem : tenantDirectory.listBlobs()) {
                if (blobItem instanceof CloudBlob blob) {
                    deleteBlob(blob);
                }
            }
        } catch (final URISyntaxException | StorageException e) {
            throw new ArtifactStoreException("Failed to delete tenant directory from Azure storage", e);
        }
    }

    @Override
    public boolean existsBySha1(final String tenant, final String sha1Hash) {
        try {
            return getBlob(tenant, sha1Hash).exists();
        } catch (final StorageException | URISyntaxException e) {
            LOG.warn("Caught exception while calling getBlob() for tenant: {} and sha1Hash: {}", tenant, sha1Hash,
                    e);
            return false;
        }
    }

    private CloudBlockBlob getBlob(final String tenant, final String sha1Hash16)
            throws URISyntaxException, StorageException {
        final CloudBlobContainer container = getContainer();
        final CloudBlobDirectory tenantDirectory = container.getDirectoryReference(sanitizeTenant(tenant));
        return tenantDirectory.getBlockBlobReference(sha1Hash16);
    }

    private void deleteBlob(final CloudBlob blob) {
        try {
            blob.delete();
        } catch (final StorageException e) {
            throw new ArtifactStoreException("Failed to delete tenant directory from Azure storage", e);
        }
    }
}

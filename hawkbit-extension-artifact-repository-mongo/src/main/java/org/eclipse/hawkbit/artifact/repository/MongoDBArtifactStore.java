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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.hawkbit.artifact.AbstractArtifactStorage;
import org.eclipse.hawkbit.artifact.exception.ArtifactBinaryNotFoundException;
import org.eclipse.hawkbit.artifact.exception.ArtifactStoreException;
import org.eclipse.hawkbit.artifact.model.ArtifactHashes;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

import com.mongodb.MongoClientException;
import com.mongodb.MongoException;
import com.mongodb.client.gridfs.model.GridFSFile;

/**
 * The file management based on MongoDb GridFS.
 */
@Validated
public class MongoDBArtifactStore extends AbstractArtifactStorage {
    /**
     * The mongoDB field which holds the filename of the file to download.
     * hawkBit update-server uses the SHA hash as a filename and lookup in the
     * mongoDB.
     */
    private static final String FILENAME = "filename";

    /**
     * The mongoDB field which holds the tenant of the file to download.
     */
    private static final String TENANT = "tenant";

    /**
     * Query by tenant metadata field.
     */
    private static final String TENANT_QUERY = "metadata." + TENANT;

    /**
     * The mongoDB field which holds the SHA1 hash, stored in the meta data
     * object.
     */
    private static final String SHA1 = "sha1";

    private static final String ID = "_id";

    private static final String CONTENT_TYPE = "contentType";

    private final GridFsOperations gridFs;

    MongoDBArtifactStore(final GridFsOperations gridFs) {
        Assert.notNull(gridFs, "gridFs cannot be null");
        this.gridFs = gridFs;
    }

    @Override
    public InputStream getBySha1(final String tenant, final String sha1Hash) {
        try {
            GridFSFile found = findArtifact(tenant, sha1Hash);

            // fallback pre-multi-tenancy
            if (found == null) {
                found = gridFs.findOne(
                        new Query().addCriteria(Criteria.where(FILENAME).is(sha1Hash).and(TENANT_QUERY).exists(false)));
            }

            if (found == null) {
                throw new ArtifactBinaryNotFoundException(sha1Hash);
            }

            final GridFsResource resource = gridFs.getResource(found);
            return resource.getInputStream();
        } catch (final ArtifactBinaryNotFoundException e) {
            throw e;
        } catch (final MongoClientException | IOException | IllegalStateException e) {
            throw new ArtifactStoreException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteBySha1(final String tenant, final String sha1Hash) {
        try {
            deleteArtifact(findArtifact(tenant, sha1Hash));
        } catch (final MongoException e) {
            throw new ArtifactStoreException(e.getMessage(), e);
        }
    }

    @Override
    protected void store(final String tenant, final ArtifactHashes base16Hashes, final String contentType,
            final File tempFile) throws IOException {
        final GridFSFile result = findArtifact(tenant, base16Hashes.sha1());
        if (result != null) {
            return;
        }

        try (InputStream inputStream = new FileInputStream(tempFile)) {
            final Document metadata = new Document();
            metadata.put(SHA1, base16Hashes.sha1());
            metadata.put(TENANT, sanitizeTenant(tenant));
            metadata.put(FILENAME, base16Hashes.sha1());
            metadata.put(CONTENT_TYPE, contentType);

            final ObjectId id = gridFs.store(inputStream, base16Hashes.sha1(), contentType, metadata);
            if (gridFs.findOne(new Query().addCriteria(Criteria.where(ID).is(id))) == null) {
                throw new ArtifactStoreException("Could not load stored GridFS file for " + base16Hashes.sha1());
            }
        } catch (final MongoClientException e) {
            throw new ArtifactStoreException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteByTenant(final String tenant) {
        try {
            gridFs.delete(new Query().addCriteria(Criteria.where(TENANT_QUERY).is(sanitizeTenant(tenant))));
        } catch (final MongoClientException e) {
            throw new ArtifactStoreException(e.getMessage(), e);
        }
    }

    @Override
    public boolean existsBySha1(final String tenant, final String sha1Hash) {
        return findArtifact(tenant, sha1Hash) != null;
    }

    private GridFSFile findArtifact(final String tenant, final String sha1Hash) {
        return gridFs.findOne(new Query()
                .addCriteria(Criteria.where(FILENAME).is(sha1Hash).and(TENANT_QUERY).is(sanitizeTenant(tenant))));
    }

    private void deleteArtifact(final GridFSFile file) {
        if (file != null) {
            try {
                gridFs.delete(new Query().addCriteria(Criteria.where(ID).is(file.getId())));
            } catch (final MongoClientException e) {
                throw new ArtifactStoreException(e.getMessage(), e);
            }
        }
    }
}

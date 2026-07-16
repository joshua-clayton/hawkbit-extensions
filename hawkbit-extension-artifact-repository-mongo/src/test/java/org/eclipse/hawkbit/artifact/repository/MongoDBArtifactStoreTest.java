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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;

import org.eclipse.hawkbit.artifact.TestConfiguration;
import org.eclipse.hawkbit.artifact.exception.ArtifactBinaryNotFoundException;
import org.eclipse.hawkbit.artifact.model.ArtifactHashes;
import org.eclipse.hawkbit.artifact.model.StoredArtifactInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;

@Feature("Component Tests - Repository")
@Story("Artifact Store MongoDB")
@SpringBootTest(classes = {MongoDBArtifactStoreAutoConfiguration.class, TestConfiguration.class}, properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.0"})
public class MongoDBArtifactStoreTest {
    private static final String TENANT = "test_tenant";
    private static final String TENANT2 = "test_tenant2";

    @Autowired
    private MongoDBArtifactStore artifactStoreUnderTest;

    @Test
    @Description("Ensures that search by SHA1 hash (which is used by hawkBit as artifact ID) finds the expected results.")
    public void findArtifactBySHA1Hash() throws NoSuchAlgorithmException, IOException {
        final String sha1 = storeRandomArtifactAndVerify(TENANT);
        final String sha2 = storeRandomArtifactAndVerify(TENANT2);

        assertThatThrownBy(() -> artifactStoreUnderTest.getBySha1(TENANT2, sha1))
                .isInstanceOf(ArtifactBinaryNotFoundException.class);
        assertThatThrownBy(() -> artifactStoreUnderTest.getBySha1(TENANT, sha2))
                .isInstanceOf(ArtifactBinaryNotFoundException.class);
    }

    @Test
    @Description("Deletes file from repository identified by SHA1 hash as filename.")
    public void deleteArtifactBySHA1Hash() throws NoSuchAlgorithmException, IOException {
        final String sha1 = storeRandomArtifactAndVerify(TENANT);

        artifactStoreUnderTest.deleteBySha1(TENANT, sha1);
        assertThatThrownBy(() -> artifactStoreUnderTest.getBySha1(TENANT, sha1))
                .isInstanceOf(ArtifactBinaryNotFoundException.class);
    }

    @Test
    @Description("Verfies that all data of a tenant is erased if repository is asked to do so. "
            + "Data of other tenants is not affected.")
    public void deleteTenant() throws NoSuchAlgorithmException, IOException {
        final String shaDeleted = storeRandomArtifactAndVerify(TENANT);
        final String shaUndeleted = storeRandomArtifactAndVerify("another_tenant");

        artifactStoreUnderTest.deleteByTenant("tenant_that_does_not_exist");
        artifactStoreUnderTest.deleteByTenant(TENANT);
        assertThatThrownBy(() -> artifactStoreUnderTest.getBySha1(TENANT, shaDeleted))
                .isInstanceOf(ArtifactBinaryNotFoundException.class);
        try (InputStream is = artifactStoreUnderTest.getBySha1("another_tenant", shaUndeleted)) {
            assertThat(is).isNotNull();
        }
    }

    @Test
    @Description("Verfies that artifacts with equal binary content are only stored once.")
    public void storeSameArtifactMultipleTimes() throws NoSuchAlgorithmException, IOException {
        final byte[] bytes = new byte[128];
        new Random().nextBytes(bytes);

        final MessageDigest mdSHA1 = MessageDigest.getInstance("SHA1");
        final MessageDigest mdSHA256 = MessageDigest.getInstance("SHA-256");
        final MessageDigest mdMD5 = MessageDigest.getInstance("MD5");
        final HexFormat hexFormat = HexFormat.of().withLowerCase();
        final ArtifactHashes hash = new ArtifactHashes(hexFormat.formatHex(mdSHA1.digest(bytes)),
                hexFormat.formatHex(mdMD5.digest(bytes)), hexFormat.formatHex(mdSHA256.digest(bytes)));

        final StoredArtifactInfo result1 = storeArtifact(TENANT, "file1.txt", new ByteArrayInputStream(bytes), mdSHA1,
                mdMD5, hash);
        final StoredArtifactInfo result2 = storeArtifact(TENANT, "file2.bla", new ByteArrayInputStream(bytes), mdSHA1,
                mdMD5, hash);
        assertThat(result1.getHashes().sha1()).isEqualTo(result2.getHashes().sha1());
    }

    @Step
    private String storeRandomArtifactAndVerify(final String tenant) throws NoSuchAlgorithmException, IOException {
        final int filelengthBytes = 128;
        final String filename = "testfile.json";
        final MessageDigest mdSHA1 = MessageDigest.getInstance("SHA1");
        final MessageDigest mdMD5 = MessageDigest.getInstance("MD5");

        final StoredArtifactInfo stored = storeArtifact(tenant, filename, generateInputStream(filelengthBytes), mdSHA1,
                mdMD5, null);

        final HexFormat hexFormat = HexFormat.of().withLowerCase();
        final String sha1Hash16 = hexFormat.formatHex(mdSHA1.digest());

        assertThat(stored.getContentType()).isEqualTo("application/json");
        assertThat(stored.getHashes().sha1()).isEqualTo(sha1Hash16);
        assertThat(stored.getSize()).isEqualTo(filelengthBytes);
        try (InputStream is = artifactStoreUnderTest.getBySha1(tenant, sha1Hash16)) {
            assertThat(is).isNotNull();
        }

        return sha1Hash16;
    }

    private StoredArtifactInfo storeArtifact(final String tenant, final String filename, final InputStream content,
            final MessageDigest sha1, final MessageDigest md5, final ArtifactHashes hash)
            throws NoSuchAlgorithmException, IOException {
        try (DigestInputStream digestInputStream = wrapInDigestInputStream(content, sha1, md5)) {
            return artifactStoreUnderTest.store(tenant, digestInputStream, filename, "application/json", hash);
        }
    }

    private static ByteArrayInputStream generateInputStream(final int length) {
        final byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        return new ByteArrayInputStream(bytes);
    }

    private static DigestInputStream wrapInDigestInputStream(final InputStream input, final MessageDigest mdSHA1,
            final MessageDigest mdMD5) {
        return new DigestInputStream(new DigestInputStream(input, mdMD5), mdSHA1);
    }

}

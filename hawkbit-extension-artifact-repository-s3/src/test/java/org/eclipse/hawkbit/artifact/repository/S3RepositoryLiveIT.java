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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import org.eclipse.hawkbit.artifact.model.ArtifactHashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * Exercises {@link S3Repository} against a real S3 bucket. Not picked up by
 * the default {@code mvn test} run (surefire's default includes don't match
 * {@code *IT.java}) — run explicitly with a real bucket configured:
 *
 * <pre>
 * mvn -pl hawkbit-extension-artifact-repository-s3 test \
 *     -Dtest=S3RepositoryLiveIT \
 *     -Ds3.it.bucket=&lt;bucket-name&gt;
 * </pre>
 *
 * Credentials and region are resolved via the standard AWS SDK v2 default
 * chain (env vars, container credentials incl. EKS Pod Identity, instance
 * profile, etc) — same resolution path {@link S3RepositoryAutoConfiguration}
 * uses in production.
 */
public class S3RepositoryLiveIT {

    private static final String TENANT = "S3-LIVE-IT-" + System.currentTimeMillis();

    private S3Client s3Client;
    private String bucket;
    private S3Repository s3RepositoryUnderTest;

    @BeforeEach
    public void before() {
        bucket = System.getProperty("s3.it.bucket");
        assumeTrue(bucket != null && !bucket.isBlank(),
                "Skipping: set -Ds3.it.bucket=<bucket-name> to run this against a real bucket");

        final S3RepositoryProperties s3Properties = new S3RepositoryProperties();
        s3Properties.setBucketName(bucket);

        s3Client = S3Client.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
        s3RepositoryUnderTest = new S3Repository(s3Client, s3Properties);
    }

    @AfterEach
    public void cleanup() {
        if (s3RepositoryUnderTest != null) {
            s3RepositoryUnderTest.deleteByTenant(TENANT);
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @Test
    public void storeGetAndDeleteRoundTripAgainstRealBucket() throws IOException, NoSuchAlgorithmException {
        final byte[] rndBytes = randomBytes();
        final String sha1 = getSha1OfBytes(rndBytes);
        final String md5 = getMd5OfBytes(rndBytes);
        final String sha256 = getSha256OfBytes(rndBytes);
        final String contentType = "application/octet-stream";

        assertThat(s3RepositoryUnderTest.existsBySha1(TENANT, sha1)).isFalse();

        try (InputStream content = new BufferedInputStream(new ByteArrayInputStream(rndBytes))) {
            s3RepositoryUnderTest.store(TENANT, content, "live-it-payload.bin", contentType,
                    new ArtifactHashes(sha1, md5, sha256));
        }

        assertThat(s3RepositoryUnderTest.existsBySha1(TENANT, sha1)).isTrue();

        // Directly verify the content-type landed on the real object — this is the
        // exact property a mutable-metadata bug (fixed during the v1->v2 migration)
        // could silently drop.
        final String storedContentType = s3Client.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(TENANT + "/" + sha1)
                        .build())
                .contentType();
        assertThat(storedContentType).isEqualTo(contentType);

        try (InputStream downloaded = s3RepositoryUnderTest.getBySha1(TENANT, sha1)) {
            final byte[] downloadedBytes = ByteStreams.toByteArray(downloaded);
            assertThat(downloadedBytes).isEqualTo(rndBytes);
        }

        s3RepositoryUnderTest.deleteBySha1(TENANT, sha1);

        assertThat(s3RepositoryUnderTest.existsBySha1(TENANT, sha1)).isFalse();
    }

    private static String getSha1OfBytes(final byte[] bytes) throws IOException, NoSuchAlgorithmException {
        return getHashOfBytes(bytes, MessageDigest.getInstance("SHA1"));
    }

    private static String getMd5OfBytes(final byte[] bytes) throws IOException, NoSuchAlgorithmException {
        return getHashOfBytes(bytes, MessageDigest.getInstance("MD5"));
    }

    private static String getSha256OfBytes(final byte[] bytes) throws IOException, NoSuchAlgorithmException {
        return getHashOfBytes(bytes, MessageDigest.getInstance("SHA-256"));
    }

    private static String getHashOfBytes(final byte[] bytes, final MessageDigest messageDigest) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes);
                OutputStream output = new DigestOutputStream(new ByteArrayOutputStream(), messageDigest)) {
            ByteStreams.copy(input, output);
            return BaseEncoding.base16().lowerCase().encode(messageDigest.digest());
        }
    }

    private static byte[] randomBytes() {
        final byte[] bytes = new byte[64];
        new Random().nextBytes(bytes);
        return bytes;
    }
}

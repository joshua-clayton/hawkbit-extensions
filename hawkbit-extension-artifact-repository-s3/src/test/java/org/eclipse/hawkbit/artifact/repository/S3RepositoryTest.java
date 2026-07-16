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
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

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

import org.eclipse.hawkbit.artifact.exception.HashNotMatchException;
import org.eclipse.hawkbit.artifact.model.ArtifactHashes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Test class for the {@link S3Repository}.
 */
@Feature("Unit Tests - S3 Repository")
@Story("S3 Artifact Repository")
@ExtendWith(MockitoExtension.class)
public class S3RepositoryTest {

    private static final String TENANT = "test_tenant";

    @Mock
    private S3Client s3ClientMock;

    @Mock
    private PutObjectResponse putObjectResponseMock;

    @Captor
    private ArgumentCaptor<PutObjectRequest> requestCaptor;

    private final S3RepositoryProperties s3Properties = new S3RepositoryProperties();
    private S3Repository s3RepositoryUnderTest;

    @BeforeEach
    public void before() {
        s3ClientMock = mock(S3Client.class);
        s3RepositoryUnderTest = new S3Repository(s3ClientMock, s3Properties);
    }

    @Test
    @Description("Verifies that the S3 client is called to put the object to S3 with the correct inputstream and meta-data")
    public void storeInputStreamCallAmazonS3Client() throws IOException, NoSuchAlgorithmException {
        final byte[] rndBytes = randomBytes();
        final String knownSHA1 = getSha1OfBytes(rndBytes);
        final String knownContentType = "application/octet-stream";

        when(s3ClientMock.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(s3ClientMock.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putObjectResponseMock);

        storeRandomBytes(rndBytes, knownContentType);

        Mockito.verify(s3ClientMock).putObject(requestCaptor.capture(), any(RequestBody.class));

        final PutObjectRequest putObjectRequest = requestCaptor.getValue();
        assertThat(putObjectRequest.bucket()).isEqualTo(s3Properties.getBucketName());
        assertThat(putObjectRequest.key()).isEqualTo(TENANT.toUpperCase() + "/" + knownSHA1);
        assertThat(putObjectRequest.contentType()).isEqualTo(knownContentType);
    }

    @Test
    @Description("Verifies that the S3 client is not called to put the object to S3 due the artifact already exists on S3")
    public void artifactIsNotUploadedIfAlreadyExists() throws NoSuchAlgorithmException, IOException {
        final byte[] rndBytes = randomBytes();
        final String knownContentType = "application/octet-stream";

        when(s3ClientMock.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        storeRandomBytes(rndBytes, knownContentType);

        Mockito.verify(s3ClientMock, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @Description("Verifies that a lowercase tenant is normalized to the same key as an uppercase tenant")
    public void existsBySha1NormalizesTenantCasing() {
        final String sha1 = "abc123";

        when(s3ClientMock.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        s3RepositoryUnderTest.existsBySha1("lowertenant", sha1);

        final ArgumentCaptor<HeadObjectRequest> headRequestCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        Mockito.verify(s3ClientMock).headObject(headRequestCaptor.capture());
        assertThat(headRequestCaptor.getValue().key()).isEqualTo("LOWERTENANT/" + sha1);
    }

    @Test
    @Description("Verifies that deleteByTenant normalizes the tenant when building the list prefix")
    public void deleteByTenantNormalizesTenantCasing() {
        final S3Client paginatorCapableMock = mock(S3Client.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(ListObjectsV2Response.builder().isTruncated(false).build())
                .when(paginatorCapableMock).listObjectsV2(any(ListObjectsV2Request.class));
        final S3Repository repositoryWithMock = new S3Repository(paginatorCapableMock, s3Properties);

        repositoryWithMock.deleteByTenant("lowertenant");

        final ArgumentCaptor<ListObjectsV2Request> listRequestCaptor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        Mockito.verify(paginatorCapableMock).listObjectsV2(listRequestCaptor.capture());
        assertThat(listRequestCaptor.getValue().prefix()).isEqualTo("LOWERTENANT/");
    }

    @Test
    @Description("Verifies that given SHA1 hash is checked and if not match will throw exception")
    public void sha1HashValuesAreNotTheSameThrowsException() throws IOException, NoSuchAlgorithmException {
        final byte[] rndBytes = randomBytes();
        final String knownContentType = "application/octet-stream";
        final String wrongSHA1Hash = "wrong";
        final String knownMD5 = getMd5OfBytes(rndBytes);
        final String knownSHA256 = getSha256OfBytes(rndBytes);

        try {
            storeRandomBytes(rndBytes, knownContentType, new ArtifactHashes(wrongSHA1Hash, knownMD5, knownSHA256));
            fail("Expected an HashNotMatchException, but didn't throw");
        } catch (final HashNotMatchException e) {
            assertThat(e.getHashFunction()).isEqualTo(HashNotMatchException.SHA1);
        }
    }

    @Test
    @Description("Verifies that given MD5 hash is checked and if not match will throw exception")
    public void md5HashValuesAreNotTheSameThrowsException() throws IOException, NoSuchAlgorithmException {
        final byte[] rndBytes = randomBytes();
        final String knownContentType = "application/octet-stream";
        final String knownSHA1 = getSha1OfBytes(rndBytes);
        final String wrongMD5 = "wrong";
        final String knownSHA256 = getSha256OfBytes(rndBytes);

        try {
            storeRandomBytes(rndBytes, knownContentType, new ArtifactHashes(knownSHA1, wrongMD5, knownSHA256));
            fail("Expected an HashNotMatchException, but didn't throw");
        } catch (final HashNotMatchException e) {
            assertThat(e.getHashFunction()).isEqualTo(HashNotMatchException.MD5);
        }
    }

    @Test
    @Description("Verifies that given SHA256 hash is checked and if not match will throw exception")
    public void sha256HashValuesAreNotTheSameThrowsException() throws IOException, NoSuchAlgorithmException {
        final byte[] rndBytes = randomBytes();
        final String knownContentType = "application/octet-stream";
        final String knownSHA1 = getSha1OfBytes(rndBytes);
        final String knownMD5 = getMd5OfBytes(rndBytes);
        final String wrongSHA256 = "wrong";

        try {
            storeRandomBytes(rndBytes, knownContentType, new ArtifactHashes(knownSHA1, knownMD5, wrongSHA256));
            fail("Expected an HashNotMatchException, but didn't throw");
        } catch (final HashNotMatchException e) {
            assertThat(e.getHashFunction()).isEqualTo(HashNotMatchException.SHA256);
        }
    }

    private void storeRandomBytes(final byte[] rndBytes, final String contentType)
            throws IOException, NoSuchAlgorithmException {
        storeRandomBytes(rndBytes, contentType, null);
    }

    private void storeRandomBytes(final byte[] rndBytes, final String contentType, final ArtifactHashes hashes)
            throws IOException {
        final String knownFileName = "randomBytes";
        try (InputStream content = new BufferedInputStream(new ByteArrayInputStream(rndBytes))) {
            s3RepositoryUnderTest.store(TENANT, content, knownFileName, contentType, hashes);
        }
    }

    private static String getSha1OfBytes(final byte[] bytes) throws IOException, NoSuchAlgorithmException {
        final MessageDigest messageDigest = MessageDigest.getInstance("SHA1");
        return getHashOfBytes(bytes, messageDigest);
    }

    private static String getMd5OfBytes(final byte[] bytes) throws IOException, NoSuchAlgorithmException {
        final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
        return getHashOfBytes(bytes, messageDigest);
    }

    private static String getSha256OfBytes(final byte[] bytes) throws IOException, NoSuchAlgorithmException {
        final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        return getHashOfBytes(bytes, messageDigest);
    }

    private static String getHashOfBytes(final byte[] bytes, final MessageDigest messageDigest) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes);
                OutputStream output = new DigestOutputStream(new ByteArrayOutputStream(), messageDigest)) {
            ByteStreams.copy(input, output);
            return BaseEncoding.base16().lowerCase().encode(messageDigest.digest());
        }
    }

    private static byte[] randomBytes() {
        final byte[] randomBytes = new byte[20];
        final Random ran = new Random();
        ran.nextBytes(randomBytes);
        return randomBytes;
    }
}

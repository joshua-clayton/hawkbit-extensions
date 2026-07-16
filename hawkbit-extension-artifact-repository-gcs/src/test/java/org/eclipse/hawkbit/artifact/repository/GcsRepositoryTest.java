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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;

/**
 * Test class for the {@link GcsRepository}.
 */
@ExtendWith(MockitoExtension.class)
@Feature("Unit Tests - GCS Repository")
@Story("GCS Artifact Repository")
public class GcsRepositoryTest {

    private static final String TENANT = "test_tenant";
    private final GcsRepositoryProperties gcpProperties = new GcsRepositoryProperties();

    @Mock
    private Storage gcsStorageMock;

    @Mock
    private Blob gcpObjectMock;

    @Mock
    private Blob putObjectResultMock;

    @Captor
    private ArgumentCaptor<byte[]> inputStreamCaptor;

    @Captor
    private ArgumentCaptor<BlobInfo> blobCaptor;

    private GcsRepository gcsRepositoryUnderTest;

    @BeforeEach
    public void before() {
        gcsStorageMock = mock(Storage.class);
        gcsRepositoryUnderTest = new GcsRepository(gcsStorageMock, gcpProperties);
    }

    @Test
    @Description("Verifies that the gcs storage client is called to put the object to GCS with the correct inputstream and meta-data")
    public void storeInputStreamCallGcsStorageClient() throws IOException {
        final byte[] rndBytes = randomBytes();
        final String knownContentType = "application/octet-stream";

        when(gcsStorageMock.get(anyString(), anyString())).thenReturn(null);
        when(gcsStorageMock.create(any(BlobInfo.class), any(byte[].class))).thenReturn(putObjectResultMock);

        storeRandomBytes(rndBytes, knownContentType);

        Mockito.verify(gcsStorageMock).create(blobCaptor.capture(), inputStreamCaptor.capture());

        final BlobInfo recordedObjectMetadata = blobCaptor.getValue();
        assertThat(recordedObjectMetadata.getContentType()).isEqualTo(knownContentType);
        assertThat(recordedObjectMetadata.getMd5()).isNotNull();
    }

    @Test
    @Description("Verifies that the gcs storage client is not called to put the object to GCS due the artifact already exists on GCS")
    public void artifactIsNotUploadedIfAlreadyExists() throws IOException {
        final byte[] rndBytes = randomBytes();
        final String knownContentType = "application/octet-stream";

        when(gcsStorageMock.get(anyString(), anyString())).thenReturn(gcpObjectMock);
        when(gcpObjectMock.exists()).thenReturn(true);

        storeRandomBytes(rndBytes, knownContentType);

        Mockito.verify(gcsStorageMock, never()).create(any(BlobInfo.class), any(byte[].class));
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

    private void storeRandomBytes(final byte[] rndBytes, final String contentType) throws IOException {
        storeRandomBytes(rndBytes, contentType, null);
    }

    private void storeRandomBytes(final byte[] rndBytes, final String contentType, final ArtifactHashes hashes)
            throws IOException {
        final String knownFileName = "randomBytes";
        try (InputStream content = new BufferedInputStream(new ByteArrayInputStream(rndBytes))) {
            gcsRepositoryUnderTest.store(TENANT, content, knownFileName, contentType, hashes);
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

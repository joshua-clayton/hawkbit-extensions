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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The AWS S3 configuration properties for the S3 artifact repository
 * implementation.
 */
@ConfigurationProperties("org.eclipse.hawkbit.repository.s3")
public class S3RepositoryProperties {

    private String bucketName = "artifactrepository";

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(final String bucketName) {
        this.bucketName = bucketName;
    }
}

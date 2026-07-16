# Eclipse hawkBit - Artifact Repository AWS S3
HawkBit Artifact Repository is a library for storing binary artifacts and metadata into the AWS S3 service.


## Using Artifact Repository S3 Extension
The module contains a spring-boot autoconfiguration for easily integration into spring-boot projects.
For using this extension in the hawkbit-example-application you just need to add the maven dependency.

```
<dependency>
  <groupId>org.eclipse.hawkbit</groupId>
  <artifactId>hawkbit-extension-artifact-repository-s3</artifactId>
  <version>${project.version}</version>
</dependency>
```

## Configuration of the S3 Extension

### Bucket
All files are stored in a bucket configured via property `org.eclipse.hawkbit.repository.s3.bucketName` (see S3RepositoryProperties).
The name of the object stored in the S3 bucket is the SHA1-hash of the binary file.

### S3 Credentials
The extension uses the AWS SDK v2 `DefaultCredentialsProvider` class, which looks for credentials in this order:

1. Environment variables (AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY)
2. Java system properties (aws.accessKeyId and aws.secretKey)
3. Web identity token credentials
4. Default credential profile file (~/.aws/credentials)
5. Amazon ECS / EKS Pod Identity container credentials
6. Instance profile credentials

For more information check the [Amazon credentials guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials.html).

You can exchange the credentials provider by overwriting the `AwsCredentialsProvider` bean (see S3RepositoryAutoConfiguration).

### Using with MinIO
[MinIO](https://min.io/) is a high performance, Kubernetes-friendly object storage
1. Setup MinIO with `MINIO_DOMAIN` environment variable is set. For example `minio.acme.com`
2. Create your bucket. For example `hawkbit`
3. Use Java system property `aws.s3.endpoint=http://minio.acme.com:9000` if MinIO runs on the default port and is not secured by HTTPS
4. Set up a DNS record for `hawkbit.minio.acme.com` to point to your MinIO server. Ensure `9000` port is open for HawkBit
5. When storing objects HawkBit relies on it can resolve `hawkbit.minio.acme.com` and can connect to `9000` port
6. Also set Java system property `aws.region` to any value MinIO will accept (e.g. `aws.region=us-east-1`) — unlike the AWS SDK v1, v2 requires a region to be resolvable even against a non-AWS endpoint, and building the S3 client fails outright without one

## Testing against a real bucket
`S3RepositoryLiveIT` exercises `S3Repository` against a real S3 bucket: store, verify content-type, download, delete. It's named `*IT.java` specifically so surefire's default includes (`**/*Test.java`, `**/Test*.java`, etc.) skip it — it never runs as part of `mvn test` or CI, and no failsafe plugin is configured to pick it up either.

To run it, pass a real bucket name as a system property; credentials and region resolve via the same `DefaultCredentialsProvider` chain described above:

```
mvn -pl hawkbit-extension-artifact-repository-s3 test \
    -Dtest=S3RepositoryLiveIT \
    -Ds3.it.bucket=<bucket-name>
```

Without `-Ds3.it.bucket`, the test skips itself via a JUnit assumption rather than failing. It writes under a timestamped tenant prefix and cleans up after itself in `@AfterEach`, even on failure — but if a run is killed or a future change breaks cleanup, check for `S3-LIVE-IT-*` prefixes left behind in the bucket.

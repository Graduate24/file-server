
package com.cb.file.minio;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.ByteStreams;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import io.minio.messages.*;
import io.minio.org.apache.commons.validator.routines.InetAddressValidator;
import okhttp3.*;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Simple Storage Service (aka S3) client to perform bucket and object operations.
 *
 * <h2>Bucket operations</h2>
 *
 * <ul>
 *   <li>Create, list and delete buckets.
 *   <li>Put, get and delete bucket lifecycle configuration.
 *   <li>Put, get and delete bucket policy configuration.
 *   <li>Put, get and delete bucket encryption configuration.
 *   <li>Put and get bucket default retention configuration.
 *   <li>Put and get bucket notification configuration.
 *   <li>Enable and disable bucket versioning.
 * </ul>
 *
 * <h2>Object operations</h2>
 *
 * <ul>
 *   <li>Put, get, delete and list objects.
 *   <li>Create objects by combining existing objects.
 *   <li>Put and get object retention and legal hold.
 *   <li>Filter object content by SQL statement.
 * </ul>
 *
 * <p>If access/secret keys are provided, all S3 operation requests are signed using AWS Signature
 * Version 4; else they are performed anonymously.
 *
 * <p>Examples on using this library are available <a
 * href="https://github.com/minio/minio-java/tree/master/src/test/java/io/minio/examples">here</a>.
 *
 * <p>Use {@code MinioClient.builder()} to create S3 client.
 *
 * <pre>{@code
 * // Create client with anonymous access.
 * MinioClient minioClient = MinioClient.builder().endpoint("https://play.min.io").build();
 *
 * // Create client with credentials.
 * MinioClient minioClient =
 *     MinioClient.builder()
 *         .endpoint("https://play.min.io")
 *         .credentials("Q3AM3UQ867SPQQA43P2F", "zuf+tfteSlswRu7BJ86wekitnifILbZam1KYY3TG")
 *         .build();
 * }</pre>
 */
@SuppressWarnings({"SameParameterValue", "WeakerAccess"})
public class MyClient {
    org.slf4j.Logger log = LoggerFactory.getLogger(MyClient.class);
    private static final byte[] EMPTY_BODY = new byte[]{};
    // default network I/O timeout is 5 minutes
    private static final long DEFAULT_CONNECTION_TIMEOUT = 5;
    // maximum allowed bucket policy size is 12KiB
    private static final int MAX_BUCKET_POLICY_SIZE = 12 * 1024;
    // default expiration for a presigned URL is 7 days in seconds
    private static final int DEFAULT_EXPIRY_TIME = 7 * 24 * 3600;
    private static final String DEFAULT_USER_AGENT =
            "MinIO ("
                    + System.getProperty("os.arch")
                    + "; "
                    + System.getProperty("os.arch")
                    + ") minio-java/"
                    + MinioProperties.INSTANCE.getVersion();
    private static final String END_HTTP = "----------END-HTTP----------";
    private static final String US_EAST_1 = "us-east-1";
    private static final String UPLOAD_ID = "uploadId";

    private static final Set<String> amzHeaders = new HashSet<>();

    static {
        amzHeaders.add("server-side-encryption");
        amzHeaders.add("server-side-encryption-aws-kms-key-id");
        amzHeaders.add("server-side-encryption-context");
        amzHeaders.add("server-side-encryption-customer-algorithm");
        amzHeaders.add("server-side-encryption-customer-key");
        amzHeaders.add("server-side-encryption-customer-key-md5");
        amzHeaders.add("website-redirect-location");
        amzHeaders.add("storage-class");
    }

    private static final Set<String> standardHeaders = new HashSet<>();

    static {
        standardHeaders.add("content-type");
        standardHeaders.add("cache-control");
        standardHeaders.add("content-encoding");
        standardHeaders.add("content-disposition");
        standardHeaders.add("content-language");
        standardHeaders.add("expires");
        standardHeaders.add("range");
    }

    private String userAgent = DEFAULT_USER_AGENT;
    private PrintWriter traceStream;

    private HttpUrl baseUrl;
    private String region;
    private boolean isAwsHost;
    private boolean isAcceleratedHost;
    private boolean isDualStackHost;
    private boolean useVirtualStyle;
    private String accessKey;
    private String secretKey;
    private OkHttpClient httpClient;

    private MyClient(
            HttpUrl baseUrl,
            String region,
            boolean isAwsHost,
            boolean isAcceleratedHost,
            boolean isDualStackHost,
            boolean useVirtualStyle,
            String accessKey,
            String secretKey,
            OkHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.region = region;
        this.isAwsHost = isAwsHost;
        this.isAcceleratedHost = isAcceleratedHost;
        this.isDualStackHost = isDualStackHost;
        this.useVirtualStyle = useVirtualStyle;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.httpClient = httpClient;
    }

    /**
     * Remove this constructor when all deprecated contructors are removed.
     */
    private MyClient(MyClient client) {
        this.baseUrl = client.baseUrl;
        this.region = client.region;
        this.isAwsHost = client.isAwsHost;
        this.isAcceleratedHost = client.isAcceleratedHost;
        this.isDualStackHost = client.isDualStackHost;
        this.useVirtualStyle = client.useVirtualStyle;
        this.accessKey = client.accessKey;
        this.secretKey = client.secretKey;
        this.httpClient = client.httpClient;
    }


    private void checkArgs(BaseArgs args) {
        if (args == null) {
            throw new IllegalArgumentException("null arguments");
        }
    }


    /**
     * Validates if given bucket name is DNS compatible.
     */
    private void checkBucketName(String name) throws InvalidBucketNameException {
        if (name == null) {
            throw new InvalidBucketNameException("(null)", "null bucket name");
        }

        // Bucket names cannot be no less than 3 and no more than 63 characters long.
        if (name.length() < 3 || name.length() > 63) {
            String msg = "bucket name must be at least 3 and no more than 63 characters long";
            throw new InvalidBucketNameException(name, msg);
        }
        // Successive periods in bucket names are not allowed.
        if (name.contains("..")) {
            String msg =
                    "bucket name cannot contain successive periods. For more information refer "
                            + "http://docs.aws.amazon.com/AmazonS3/latest/dev/BucketRestrictions.html";
            throw new InvalidBucketNameException(name, msg);
        }
        // Bucket names should be dns compatible.
        if (!name.matches("^[a-z0-9][a-z0-9\\.\\-]+[a-z0-9]$")) {
            String msg =
                    "bucket name does not follow Amazon S3 standards. For more information refer "
                            + "http://docs.aws.amazon.com/AmazonS3/latest/dev/BucketRestrictions.html";
            throw new InvalidBucketNameException(name, msg);
        }
    }

    private void checkObjectName(String objectName) throws IllegalArgumentException {
        if ((objectName == null) || (objectName.isEmpty())) {
            throw new IllegalArgumentException("object name cannot be empty");
        }
    }

    private void checkReadRequestSse(ServerSideEncryption sse) throws IllegalArgumentException {
        if (sse == null) {
            return;
        }

        if (sse.type() != ServerSideEncryption.Type.SSE_C) {
            throw new IllegalArgumentException("only SSE_C is supported for all read requests.");
        }

        if (sse.type().requiresTls() && !this.baseUrl.isHttps()) {
            throw new IllegalArgumentException(
                    sse.type().name() + "operations must be performed over a secure connection.");
        }
    }

    private Multimap<String, String> merge(Multimap<String, String> m1, Multimap<String, String> m2) {
        Multimap<String, String> map = HashMultimap.create();
        if (m1 != null) {
            map.putAll(m1);
        }
        if (m2 != null) {
            map.putAll(m2);
        }
        return map;
    }

    private HttpUrl buildUrl(
            Method method,
            String bucketName,
            String objectName,
            String region,
            Multimap<String, String> queryParamMap)
            throws IllegalArgumentException, InvalidBucketNameException, NoSuchAlgorithmException {
        if (bucketName == null && objectName != null) {
            throw new IllegalArgumentException("null bucket name for object '" + objectName + "'");
        }

        HttpUrl.Builder urlBuilder = this.baseUrl.newBuilder();
        String host = this.baseUrl.host();
        if (bucketName != null) {
            checkBucketName(bucketName);

            boolean enforcePathStyle = false;
            if (method == Method.PUT && objectName == null && queryParamMap == null) {
                // use path style for make bucket to workaround "AuthorizationHeaderMalformed" error from
                // s3.amazonaws.com
                enforcePathStyle = true;
            } else if (queryParamMap != null && queryParamMap.containsKey("location")) {
                // use path style for location query
                enforcePathStyle = true;
            } else if (bucketName.contains(".") && this.baseUrl.isHttps()) {
                // use path style where '.' in bucketName causes SSL certificate validation error
                enforcePathStyle = true;
            }

            if (isAwsHost) {
                String s3Domain = "s3.";
                if (isAcceleratedHost) {
                    if (bucketName.contains(".")) {
                        throw new IllegalArgumentException(
                                "bucket name '"
                                        + bucketName
                                        + "' with '.' is not allowed for accelerated endpoint");
                    }

                    if (!enforcePathStyle) {
                        s3Domain = "s3-accelerate.";
                    }
                }

                String dualStack = "";
                if (isDualStackHost) {
                    dualStack = "dualstack.";
                }

                String endpoint = s3Domain + dualStack;
                if (enforcePathStyle || !isAcceleratedHost) {
                    endpoint += region + ".";
                }

                host = endpoint + host;
            }

            if (enforcePathStyle || !useVirtualStyle) {
                urlBuilder.host(host);
                urlBuilder.addEncodedPathSegment(S3Escaper.encode(bucketName));
            } else {
                urlBuilder.host(bucketName + "." + host);
            }

            if (objectName != null) {
                // Limitation: OkHttp does not allow to add '.' and '..' as path segment.
                for (String token : objectName.split("/")) {
                    if (token.equals(".") || token.equals("..")) {
                        throw new IllegalArgumentException(
                                "object name with '.' or '..' path segment is not supported");
                    }
                }

                urlBuilder.addEncodedPathSegments(S3Escaper.encodePath(objectName));
            }
        } else {
            if (isAwsHost) {
                urlBuilder.host("s3." + region + "." + host);
            }
        }

        if (queryParamMap != null) {
            for (Map.Entry<String, String> entry : queryParamMap.entries()) {
                urlBuilder.addEncodedQueryParameter(
                        S3Escaper.encode(entry.getKey()), S3Escaper.encode(entry.getValue()));
            }
        }

        return urlBuilder.build();
    }

    private String getHostHeader(HttpUrl url) {
        // ignore port when port and service matches i.e HTTP -> 80, HTTPS -> 443
        if ((url.scheme().equals("http") && url.port() == 80)
                || (url.scheme().equals("https") && url.port() == 443)) {
            return url.host();
        }

        return url.host() + ":" + url.port();
    }

    private Request createRequest(
            HttpUrl url, Method method, Multimap<String, String> headerMap, Object body, int length)
            throws IllegalArgumentException, InsufficientDataException, InternalException, IOException,
            NoSuchAlgorithmException {
        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(url);

        String contentType = null;
        String contentEncoding = null;
        if (headerMap != null) {
            contentEncoding =
                    headerMap.get("Content-Encoding").stream()
                            .distinct()
                            .filter(encoding -> !encoding.isEmpty())
                            .collect(Collectors.joining(","));
            for (Map.Entry<String, String> entry : headerMap.entries()) {
                if (entry.getKey().equals("Content-Type")) {
                    contentType = entry.getValue();
                }

                if (!entry.getKey().equals("Content-Encoding")) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            }
        }

        if (!Strings.isNullOrEmpty(contentEncoding)) {
            requestBuilder.header("Content-Encoding", contentEncoding);
        }

        requestBuilder.header("Host", getHostHeader(url));
        // Disable default gzip compression by okhttp library.
        requestBuilder.header("Accept-Encoding", "identity");
        requestBuilder.header("User-Agent", this.userAgent);

        String sha256Hash = null;
        String md5Hash = null;
        if (this.accessKey != null && this.secretKey != null) {
            if (url.isHttps()) {
                // Fix issue #415: No need to compute sha256 if endpoint scheme is HTTPS.
                sha256Hash = "UNSIGNED-PAYLOAD";
                if (body != null) {
                    md5Hash = Digest.md5Hash(body, length);
                }
            } else {
                Object data = body;
                int len = length;
                if (data == null) {
                    data = new byte[0];
                    len = 0;
                }

                String[] hashes = Digest.sha256Md5Hashes(data, len);
                sha256Hash = hashes[0];
                md5Hash = hashes[1];
            }
        } else {
            // Fix issue #567: Compute MD5 hash only for anonymous access.
            if (body != null) {
                md5Hash = Digest.md5Hash(body, length);
            }
        }

        if (md5Hash != null) {
            requestBuilder.header("Content-MD5", md5Hash);
        }

        if (sha256Hash != null) {
            requestBuilder.header("x-amz-content-sha256", sha256Hash);
        }

        ZonedDateTime date = ZonedDateTime.now();
        requestBuilder.header("x-amz-date", date.format(Time.AMZ_DATE_FORMAT));

        RequestBody requestBody = null;
        if (body != null) {
            if (body instanceof RandomAccessFile) {
                requestBody = new HttpRequestBody((RandomAccessFile) body, length, contentType);
            } else if (body instanceof BufferedInputStream) {
                requestBody = new HttpRequestBody((BufferedInputStream) body, length, contentType);
            } else {
                requestBody = new HttpRequestBody((byte[]) body, length, contentType);
            }
        }

        requestBuilder.method(method.toString(), requestBody);
        return requestBuilder.build();
    }

    private Response execute(
            Method method,
            BaseArgs args,
            Multimap<String, String> headers,
            Multimap<String, String> queryParams,
            Object body,
            int length)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        String bucketName = null;
        String region = null;
        String objectName = null;

        if (args instanceof BucketArgs) {
            bucketName = ((BucketArgs) args).bucket();
            region = ((BucketArgs) args).region();
        }

        if (args instanceof ObjectArgs) {
            objectName = ((ObjectArgs) args).object();
        }

        return execute(
                method,
                bucketName,
                objectName,
                getRegion(bucketName, region),
                merge(args.extraHeaders(), headers),
                merge(args.extraQueryParams(), queryParams),
                body,
                length);
    }

    private Response execute(
            Method method,
            String bucketName,
            String objectName,
            String region,
            Multimap<String, String> headerMap,
            Multimap<String, String> queryParamMap,
            Object body,
            int length)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        boolean traceRequestBody = false;
        if (body != null
                && !(body instanceof InputStream
                || body instanceof RandomAccessFile
                || body instanceof byte[])) {
            byte[] bytes;
            if (body instanceof CharSequence) {
                bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                bytes = Xml.marshal(body).getBytes(StandardCharsets.UTF_8);
            }

            body = bytes;
            length = bytes.length;
            traceRequestBody = true;
        }

        if (body == null && (method == Method.PUT || method == Method.POST)) {
            body = EMPTY_BODY;
        }
        HttpUrl url = buildUrl(method, bucketName, objectName, region, queryParamMap);
        Request request = createRequest(url, method, headerMap, body, length);

        if (this.accessKey != null && this.secretKey != null) {
            request = Signer.signV4(request, region, accessKey, secretKey);
        }

        if (this.traceStream != null) {
            this.traceStream.println("---------START-HTTP---------");
            String encodedPath = request.url().encodedPath();
            String encodedQuery = request.url().encodedQuery();
            if (encodedQuery != null) {
                encodedPath += "?" + encodedQuery;
            }
            this.traceStream.println(request.method() + " " + encodedPath + " HTTP/1.1");
            String headers =
                    request
                            .headers()
                            .toString()
                            .replaceAll("Signature=([0-9a-f]+)", "Signature=*REDACTED*")
                            .replaceAll("Credential=([^/]+)", "Credential=*REDACTED*");
            this.traceStream.println(headers);
            if (traceRequestBody) {
                this.traceStream.println(new String((byte[]) body, StandardCharsets.UTF_8));
            }
        }

        OkHttpClient httpClient = this.httpClient;
        if (method == Method.PUT || method == Method.POST) {
            // Issue #924: disable connection retry for PUT and POST methods. Its safe to do
            // retry for other methods.
            httpClient = this.httpClient.newBuilder().retryOnConnectionFailure(false).build();
        }

        Response response = httpClient.newCall(request).execute();
        if (this.traceStream != null) {
            this.traceStream.println(
                    response.protocol().toString().toUpperCase(Locale.US) + " " + response.code());
            this.traceStream.println(response.headers());
        }

        if (response.isSuccessful()) {
            if (this.traceStream != null) {
                this.traceStream.println(END_HTTP);
            }
            return response;
        }

        String errorXml = null;
        try (ResponseBody responseBody = response.body()) {
            errorXml = new String(responseBody.bytes(), StandardCharsets.UTF_8);
        }

        if (this.traceStream != null && !("" .equals(errorXml) && method.equals(Method.HEAD))) {
            this.traceStream.println(errorXml);
        }

        // Error in case of Non-XML response from server for non-HEAD requests.
        String contentType = response.headers().get("content-type");
        if (!method.equals(Method.HEAD)
                && (contentType == null
                || !Arrays.asList(contentType.split(";")).contains("application/xml"))) {
            if (this.traceStream != null) {
                this.traceStream.println(END_HTTP);
            }
            throw new InvalidResponseException();
        }

        ErrorResponse errorResponse = null;
        if (!"" .equals(errorXml)) {
            errorResponse = Xml.unmarshal(ErrorResponse.class, errorXml);
        } else if (!method.equals(Method.HEAD)) {
            if (this.traceStream != null) {
                this.traceStream.println(END_HTTP);
            }
            throw new InvalidResponseException();
        }

        if (this.traceStream != null) {
            this.traceStream.println(END_HTTP);
        }

        if (errorResponse == null) {
            ErrorCode ec;
            switch (response.code()) {
                case 307:
                    ec = ErrorCode.REDIRECT;
                    break;
                case 400:
                    // HEAD bucket with wrong region gives 400 without body.
                    if (method.equals(Method.HEAD)
                            && bucketName != null
                            && objectName == null
                            && isAwsHost
                            && AwsRegionCache.INSTANCE.get(bucketName) != null) {
                        ec = ErrorCode.RETRY_HEAD_BUCKET;
                    } else {
                        ec = ErrorCode.INVALID_URI;
                    }
                    break;
                case 404:
                    if (objectName != null) {
                        ec = ErrorCode.NO_SUCH_KEY;
                    } else if (bucketName != null) {
                        ec = ErrorCode.NO_SUCH_BUCKET;
                    } else {
                        ec = ErrorCode.RESOURCE_NOT_FOUND;
                    }
                    break;
                case 501:
                case 405:
                    ec = ErrorCode.METHOD_NOT_ALLOWED;
                    break;
                case 409:
                    if (bucketName != null) {
                        ec = ErrorCode.NO_SUCH_BUCKET;
                    } else {
                        ec = ErrorCode.RESOURCE_CONFLICT;
                    }
                    break;
                case 403:
                    ec = ErrorCode.ACCESS_DENIED;
                    break;
                default:
                    if (response.code() >= 500) {
                        throw new ServerException("server failed with HTTP status code " + response.code());
                    }

                    throw new InternalException(
                            "unhandled HTTP code "
                                    + response.code()
                                    + ".  Please report this issue at "
                                    + "https://github.com/minio/minio-java/issues");
            }

            errorResponse =
                    new ErrorResponse(
                            ec,
                            bucketName,
                            objectName,
                            request.url().encodedPath(),
                            response.header("x-amz-request-id"),
                            response.header("x-amz-id-2"));
        }

        // invalidate region cache if needed
        if (errorResponse.errorCode() == ErrorCode.NO_SUCH_BUCKET
                || errorResponse.errorCode() == ErrorCode.RETRY_HEAD_BUCKET) {
//            if (isAwsHost) {
//                AwsRegionCache.INSTANCE.remove(bucketName);
//            }

            // TODO: handle for other cases as well
        }

        throw new ErrorResponseException(errorResponse, response);
    }

    /**
     * Returns region of given bucket either from region cache or set in constructor.
     */
    private String getRegion(String bucketName, String region)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        if (region != null) {
            // Error out if region does not match with region passed via constructor.
            if (this.region != null && !this.region.equals(region)) {
                throw new IllegalArgumentException(
                        "region must be " + this.region + ", but passed " + region);
            }
            return region;
        }

        if (this.region != null && !this.region.equals("")) {
            return this.region;
        }

        if (!isAwsHost || bucketName == null || this.accessKey == null) {
            return US_EAST_1;
        }

        region = AwsRegionCache.INSTANCE.get(bucketName);
        if (region != null) {
            return region;
        }

        // Execute GetBucketLocation REST API to get region of the bucket.
        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("location", null);

        Response response =
                execute(Method.GET, bucketName, null, US_EAST_1, null, queryParams, null, 0);

        try (ResponseBody body = response.body()) {
            LocationConstraint lc = Xml.unmarshal(LocationConstraint.class, body.charStream());
            if (lc.location() == null || lc.location().equals("")) {
                region = US_EAST_1;
            } else if (lc.location().equals("EU")) {
                region = "eu-west-1"; // eu-west-1 is also referred as 'EU'.
            } else {
                region = lc.location();
            }
        }

        AwsRegionCache.INSTANCE.set(bucketName, region);
        return region;
    }

    private Response executeGet(
            BaseArgs args, Multimap<String, String> headers, Multimap<String, String> queryParams)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        return execute(Method.GET, args, headers, queryParams, null, 0);
    }

    private Response executeHead(
            BaseArgs args, Multimap<String, String> headers, Multimap<String, String> queryParams)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        try {
            Response response = execute(Method.HEAD, args, headers, queryParams, null, 0);
            response.body().close();
            return response;
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.RETRY_HEAD_BUCKET) {
                throw e;
            }
        }

        // Retry once for RETRY_HEAD_BUCKET error.
        Response response = execute(Method.HEAD, args, headers, queryParams, null, 0);
        response.body().close();
        return response;
    }

    private Response executeDelete(
            BaseArgs args, Multimap<String, String> headers, Multimap<String, String> queryParams)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        Response response = execute(Method.DELETE, args, headers, queryParams, null, 0);
        response.body().close();
        return response;
    }

    private Response executePost(
            BaseArgs args,
            Multimap<String, String> headers,
            Multimap<String, String> queryParams,
            Object data)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        return execute(Method.POST, args, headers, queryParams, data, 0);
    }

    private Response executePut(
            BaseArgs args,
            Multimap<String, String> headers,
            Multimap<String, String> queryParams,
            Object data,
            int length)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        return execute(Method.PUT, args, headers, queryParams, data, length);
    }

    /**
     * Gets information of an object.
     *
     * <pre>Example:{@code
     * // Get information of an object.
     * ObjectStat objectStat =
     *     minioClient.statObject(
     *         StatObjectArgs.builder().bucket("my-bucketname").object("my-objectname").build());
     *
     * // Get information of SSE-C encrypted object.
     * ObjectStat objectStat =
     *     minioClient.statObject(
     *         StatObjectArgs.builder()
     *             .bucket("my-bucketname")
     *             .object("my-objectname")
     *             .ssec(ssec)
     *             .build());
     *
     * // Get information of a versioned object.
     * ObjectStat objectStat =
     *     minioClient.statObject(
     *         StatObjectArgs.builder()
     *             .bucket("my-bucketname")
     *             .object("my-objectname")
     *             .versionId("version-id")
     *             .build());
     *
     * // Get information of a SSE-C encrypted versioned object.
     * ObjectStat objectStat =
     *     minioClient.statObject(
     *         StatObjectArgs.builder()
     *             .bucket("my-bucketname")
     *             .object("my-objectname")
     *             .versionId("version-id")
     *             .ssec(ssec)
     *             .build());
     * }</pre>
     *
     * @param args {@link StatObjectArgs} object.
     * @return {@link ObjectStat} - Populated object information and metadata.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     * @see ObjectStat
     */
    public ObjectStat statObject(StatObjectArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);
        // TODO
        // args.validateSsec(baseUrl);

        Multimap<String, String> ssecHeaders = null;
        if (args.ssec() != null) {
            ssecHeaders = Multimaps.forMap(args.ssec().headers());
        }

        Multimap<String, String> queryParams = HashMultimap.create();
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        Response response = executeHead(args, ssecHeaders, queryParams);
        return new ObjectStat(args.bucket(), args.object(), response.headers());
    }

    /**
     * Gets URL of an object useful when this object has public read access.
     *
     * <pre>Example:{@code
     * String url = minioClient.getObjectUrl("my-bucketname", "my-objectname");
     * }</pre>
     *
     * @param bucketName Name of the bucket.
     * @param objectName Object name in the bucket.
     * @return String - URL string.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public String getObjectUrl(String bucketName, String objectName)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkObjectName(objectName);
        HttpUrl url =
                buildUrl(Method.GET, bucketName, objectName, getRegion(bucketName, this.region), null);
        return url.toString();
    }


    /**
     * Gets data from offset to length of a SSE-C encrypted object. Returned {@link InputStream} must
     * be closed after use to release network resources.
     *
     * <pre>Example:{@code
     * try (InputStream stream =
     *     minioClient.getObject(
     *   GetObjectArgs.builder()
     *     .bucket("my-bucketname")
     *     .object("my-objectname")
     *     .offset(offset)
     *     .length(len)
     *     .ssec(ssec)
     *     .build()
     * ) {
     *   // Read data from stream
     * }
     * }</pre>
     *
     * @param args Object of {@link GetObjectArgs}
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public InputStream getObject(GetObjectArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);
        // TODO
        // args.validateSsec(this.baseUrl);

        Long offset = args.offset();
        Long length = args.length();
        if (length != null && offset == null) {
            offset = 0L;
        }

        Multimap<String, String> headers = HashMultimap.create();
        if (length != null) {
            headers.put("Range", "bytes=" + offset + "-" + (offset + length - 1));
        } else if (offset != null) {
            headers.put("Range", "bytes=" + offset + "-");
        }

        if (args.ssec() != null) {
            headers.putAll(Multimaps.forMap(args.ssec().headers()));
        }

        Multimap<String, String> queryParams = HashMultimap.create();
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        Response response = executeGet(args, headers, queryParams);
        return response.body().byteStream();
    }

    /**
     * Downloads data of a SSE-C encrypted object to file.
     *
     * <pre>Example:{@code
     * minioClient.downloadObject(
     *   GetObjectArgs.builder()
     *     .bucket("my-bucketname")
     *     .object("my-objectname")
     *     .ssec(ssec)
     *     .fileName("my-filename")
     *     .build());
     * }</pre>
     *
     * @param args Object of {@link DownloadObjectArgs}
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void downloadObject(DownloadObjectArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        String filename = args.filename();
        Path filePath = Paths.get(filename);
        boolean fileExists = Files.exists(filePath);

        ObjectStat objectStat = statObject(new StatObjectArgs(args));
        long length = objectStat.length();
        String etag = objectStat.etag();

        String tempFilename = filename + "." + etag + ".part.minio";
        Path tempFilePath = Paths.get(tempFilename);
        boolean tempFileExists = Files.exists(tempFilePath);

        if (tempFileExists && !Files.isRegularFile(tempFilePath)) {
            throw new IOException(tempFilename + ": not a regular file");
        }

        long tempFileSize = 0;
        if (tempFileExists) {
            tempFileSize = Files.size(tempFilePath);
            if (tempFileSize > length) {
                Files.delete(tempFilePath);
                tempFileExists = false;
                tempFileSize = 0;
            }
        }

        if (fileExists) {
            long fileSize = Files.size(filePath);
            if (fileSize == length) {
                // already downloaded. nothing to do
                return;
            } else if (fileSize > length) {
                throw new IllegalArgumentException(
                        "Source object, '"
                                + args.object()
                                + "', size:"
                                + length
                                + " is smaller than the destination file, '"
                                + filename
                                + "', size:"
                                + fileSize);
            } else if (!tempFileExists) {
                // before resuming the download, copy filename to tempfilename
                Files.copy(filePath, tempFilePath);
                tempFileSize = fileSize;
                tempFileExists = true;
            }
        }

        InputStream is = null;
        OutputStream os = null;
        try {
            is = getObject(new GetObjectArgs(args));
            os =
                    Files.newOutputStream(tempFilePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            long bytesWritten = ByteStreams.copy(is, os);
            is.close();
            os.close();

            if (bytesWritten != length - tempFileSize) {
                throw new IOException(
                        tempFilename
                                + ": unexpected data written.  expected = "
                                + (length - tempFileSize)
                                + ", written = "
                                + bytesWritten);
            }
            Files.move(tempFilePath, filePath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (is != null) {
                is.close();
            }
            if (os != null) {
                os.close();
            }
        }
    }


    private int calculatePartCount(List<ComposeSource> sources)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        long objectSize = 0;
        int partCount = 0;
        int i = 0;
        for (ComposeSource src : sources) {
            i++;
            ObjectStat stat = statObject(new StatObjectArgs(src));

            src.buildHeaders(stat.length(), stat.etag());

            long size = stat.length();
            if (src.length() != null) {
                size = src.length();
            } else if (src.offset() != null) {
                size -= src.offset();
            }

            if (size < ObjectWriteArgs.MIN_MULTIPART_SIZE && sources.size() != 1 && i != sources.size()) {
                throw new IllegalArgumentException(
                        "source "
                                + src.bucket()
                                + "/"
                                + src.object()
                                + ": size "
                                + size
                                + " must be greater than "
                                + ObjectWriteArgs.MIN_MULTIPART_SIZE);
            }

            objectSize += size;
            if (objectSize > ObjectWriteArgs.MAX_OBJECT_SIZE) {
                throw new IllegalArgumentException(
                        "destination object size must be less than " + ObjectWriteArgs.MAX_OBJECT_SIZE);
            }

            if (size > ObjectWriteArgs.MAX_PART_SIZE) {
                long count = size / ObjectWriteArgs.MAX_PART_SIZE;
                long lastPartSize = size - (count * ObjectWriteArgs.MAX_PART_SIZE);
                if (lastPartSize > 0) {
                    count++;
                } else {
                    lastPartSize = ObjectWriteArgs.MAX_PART_SIZE;
                }

                if (lastPartSize < ObjectWriteArgs.MIN_MULTIPART_SIZE
                        && sources.size() != 1
                        && i != sources.size()) {
                    throw new IllegalArgumentException(
                            "source "
                                    + src.bucket()
                                    + "/"
                                    + src.object()
                                    + ": "
                                    + "for multipart split upload of "
                                    + size
                                    + ", last part size is less than "
                                    + ObjectWriteArgs.MIN_MULTIPART_SIZE);
                }
                partCount += (int) count;
            } else {
                partCount++;
            }

            if (partCount > ObjectWriteArgs.MAX_MULTIPART_COUNT) {
                throw new IllegalArgumentException(
                        "Compose sources create more than allowed multipart count "
                                + ObjectWriteArgs.MAX_MULTIPART_COUNT);
            }
        }

        return partCount;
    }

    /**
     * Creates an object by combining data from different source objects using server-side copy.
     *
     * <pre>Example:{@code
     * List<ComposeSource> sourceObjectList = new ArrayList<ComposeSource>();
     *
     * sourceObjectList.add(
     *    ComposeSource.builder().bucket("my-job-bucket").object("my-objectname-part-one").build());
     * sourceObjectList.add(
     *    ComposeSource.builder().bucket("my-job-bucket").object("my-objectname-part-two").build());
     * sourceObjectList.add(
     *    ComposeSource.builder().bucket("my-job-bucket").object("my-objectname-part-three").build());
     *
     * // Create my-bucketname/my-objectname by combining source object list.
     * minioClient.composeObject(
     *    ComposeObjectArgs.builder()
     *        .bucket("my-bucketname")
     *        .object("my-objectname")
     *        .sources(sourceObjectList)
     *        .build());
     *
     * // Create my-bucketname/my-objectname with user metadata by combining source object
     * // list.
     * minioClient.composeObject(
     *     ComposeObjectArgs.builder()
     *        .bucket("my-bucketname")
     *        .object("my-objectname")
     *        .sources(sourceObjectList)
     *        .build());
     *
     * // Create my-bucketname/my-objectname with user metadata and server-side encryption
     * // by combining source object list.
     * minioClient.composeObject(
     *   ComposeObjectArgs.builder()
     *        .bucket("my-bucketname")
     *        .object("my-objectname")
     *        .sources(sourceObjectList)
     *        .ssec(sse)
     *        .build());
     *
     * }</pre>
     *
     * @param args {@link ComposeObjectArgs} object.
     * @return {@link ObjectWriteResponse} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public ObjectWriteResponse composeObject(ComposeObjectArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);
        args.validateSse(this.baseUrl);
        List<ComposeSource> sources = args.sources();
        int partCount = calculatePartCount(sources);
        Multimap<String, String> headers = HashMultimap.create();
        headers.putAll(args.extraHeaders());
        headers.putAll(args.genHeaders());
        String uploadId =
                createMultipartUpload(
                        args.bucket(), args.region(), args.object(), headers, args.extraQueryParams());

        Multimap<String, String> ssecHeaders = HashMultimap.create();
        if (args.sse() != null && args.sse().type() == ServerSideEncryption.Type.SSE_C) {
            ssecHeaders.putAll(Multimaps.forMap(args.sse().headers()));
        }

        try {
            int partNumber = 0;
            Part[] totalParts = new Part[partCount];
            for (ComposeSource src : sources) {
                long size = src.objectSize();
                if (src.length() != null) {
                    size = src.length();
                } else if (src.offset() != null) {
                    size -= src.offset();
                }
                long offset = 0;
                if (src.offset() != null) {
                    offset = src.offset();
                }

                headers = HashMultimap.create();
                headers.putAll(src.headers());
                headers.putAll(ssecHeaders);

                if (size <= ObjectWriteArgs.MAX_PART_SIZE) {
                    partNumber++;
                    if (src.length() != null) {
                        headers.put(
                                "x-amz-copy-source-range", "bytes=" + offset + "-" + (offset + src.length() - 1));
                    } else if (src.offset() != null) {
                        headers.put("x-amz-copy-source-range", "bytes=" + offset + "-" + (offset + size - 1));
                    }

                    String eTag = uploadPartCopy(args.bucket(), args.object(), uploadId, partNumber, headers);

                    totalParts[partNumber - 1] = new Part(partNumber, eTag);
                    continue;
                }

                while (size > 0) {
                    partNumber++;

                    long startBytes = offset;
                    long endBytes = startBytes + ObjectWriteArgs.MAX_PART_SIZE;
                    if (size < ObjectWriteArgs.MAX_PART_SIZE) {
                        endBytes = startBytes + size;
                    }

                    Multimap<String, String> headersCopy = HashMultimap.create();
                    headers.putAll(headers);
                    headers.put("x-amz-copy-source-range", "bytes=" + startBytes + "-" + endBytes);

                    String eTag =
                            uploadPartCopy(args.bucket(), args.object(), uploadId, partNumber, headersCopy);
                    totalParts[partNumber - 1] = new Part(partNumber, eTag);
                    offset = startBytes;
                    size -= (endBytes - startBytes);
                }
            }

            return completeMultipartUpload(
                    args.bucket(),
                    getRegion(args.bucket(), args.region()),
                    args.object(),
                    uploadId,
                    totalParts,
                    null,
                    null);
        } catch (RuntimeException e) {
            abortMultipartUpload(args.bucket(), args.object(), uploadId);
            throw e;
        } catch (Exception e) {
            abortMultipartUpload(args.bucket(), args.object(), uploadId);
            throw e;
        }
    }

    /**
     * Gets presigned URL of an object for HTTP method, expiry time and custom request parameters.
     *
     * <pre>Example:{@code
     * // Get presigned URL string to delete 'my-objectname' in 'my-bucketname' and its life time
     * // is one day.
     * String url =
     *    minioClient.getPresignedObjectUrl(
     *        GetPresignedObjectUrlArgs.builder()
     *            .method(Method.DELETE)
     *            .bucket("my-bucketname")
     *            .object("my-objectname")
     *            .expiry(24 * 60 * 60)
     *            .build());
     * System.out.println(url);
     *
     * // Get presigned URL string to upload 'my-objectname' in 'my-bucketname'
     * // with response-content-type as application/json and life time as one day.
     * Map<String, String> reqParams = new HashMap<String, String>();
     * reqParams.put("response-content-type", "application/json");
     *
     * String url =
     *    minioClient.getPresignedObjectUrl(
     *        GetPresignedObjectUrlArgs.builder()
     *            .method(Method.PUT)
     *            .bucket("my-bucketname")
     *            .object("my-objectname")
     *            .expiry(1, TimeUnit.DAYS)
     *            .extraQueryParams(reqParams)
     *            .build());
     * System.out.println(url);
     *
     * // Get presigned URL string to download 'my-objectname' in 'my-bucketname' and its life time
     * // is 2 hours.
     * String url =
     *    minioClient.getPresignedObjectUrl(
     *        GetPresignedObjectUrlArgs.builder()
     *            .method(Method.GET)
     *            .bucket("my-bucketname")
     *            .object("my-objectname")
     *            .expiry(2, TimeUnit.HOURS)
     *            .build());
     * System.out.println(url);
     * }</pre>
     *
     * @param args {@link GetPresignedObjectUrlArgs} object.
     * @return String - URL string.
     * @throws ErrorResponseException       thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException     throws to indicate invalid argument passed.
     * @throws InsufficientDataException    thrown to indicate not enough data available in InputStream.
     * @throws InternalException            thrown to indicate internal library error.
     * @throws InvalidBucketNameException   thrown to indicate invalid bucket name passed.
     * @throws InvalidExpiresRangeException thrown to indicate invalid expiry duration passed.
     * @throws InvalidKeyException          thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException     thrown to indicate S3 service returned invalid or no error
     *                                      response.
     * @throws IOException                  thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException     thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException           thrown to indicate XML parsing error.
     * @throws ServerException
     */
    public String getPresignedObjectUrl(GetPresignedObjectUrlArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidExpiresRangeException,
            InvalidKeyException, InvalidResponseException, IOException, NoSuchAlgorithmException,
            XmlParserException, ServerException {
        checkArgs(args);

        byte[] body = null;
        if (args.method() == Method.PUT || args.method() == Method.POST) {
            body = EMPTY_BODY;
        }

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.putAll(args.extraQueryParams());
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        String region = getRegion(args.bucket(), args.region());
        HttpUrl url = buildUrl(args.method(), args.bucket(), args.object(), region, queryParams);
        Request request = createRequest(url, args.method(), null, body, 0);
        url = Signer.presignV4(request, region, accessKey, secretKey, args.expiry());
        return url.toString();
    }

    /**
     * Gets form-data of {@link PostPolicy} of an object to upload its data using POST method.
     *
     * <pre>Example:{@code
     * PostPolicy policy = new PostPolicy("my-bucketname", "my-objectname",
     *     ZonedDateTime.now().plusDays(7));
     *
     * // 'my-objectname' should be 'image/png' content type
     * policy.setContentType("image/png");
     *
     * // set success action status to 201 to receive XML document
     * policy.setSuccessActionStatus(201);
     *
     * Map<String,String> formData = minioClient.presignedPostPolicy(policy);
     *
     * // Print curl command to be executed by anonymous user to upload /tmp/userpic.png.
     * System.out.print("curl -X POST ");
     * for (Map.Entry<String,String> entry : formData.entrySet()) {
     *   System.out.print(" -F " + entry.getKey() + "=" + entry.getValue());
     * }
     * System.out.println(" -F file=@/tmp/userpic.png https://play.min.io/my-bucketname");
     * }</pre>
     *
     * @param policy Post policy of an object.
     * @return Map&ltString, String&gt - Contains form-data to upload an object using POST method.
     * @throws ErrorResponseException       thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException     throws to indicate invalid argument passed.
     * @throws InsufficientDataException    thrown to indicate not enough data available in InputStream.
     * @throws InternalException            thrown to indicate internal library error.
     * @throws InvalidBucketNameException   thrown to indicate invalid bucket name passed.
     * @throws InvalidExpiresRangeException thrown to indicate invalid expiry duration passed.
     * @throws InvalidKeyException          thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException     thrown to indicate S3 service returned invalid or no error
     *                                      response.
     * @throws IOException                  thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException     thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException           thrown to indicate XML parsing error.
     * @see PostPolicy
     */
    public Map<String, String> presignedPostPolicy(PostPolicy policy)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidExpiresRangeException,
            InvalidKeyException, InvalidResponseException, IOException, NoSuchAlgorithmException,
            ServerException, XmlParserException {
        return policy.formData(this.accessKey, this.secretKey, getRegion(policy.bucketName(), null));
    }

    /**
     * Removes an object.
     *
     * <pre>Example:{@code
     * // Remove object.
     * minioClient.removeObject(
     *     RemoveObjectArgs.builder().bucket("my-bucketname").object("my-objectname").build());
     *
     * // Remove versioned object.
     * minioClient.removeObject(
     *     RemoveObjectArgs.builder()
     *         .bucket("my-bucketname")
     *         .object("my-versioned-objectname")
     *         .versionId("my-versionid")
     *         .build());
     *
     * // Remove versioned object bypassing Governance mode.
     * minioClient.removeObject(
     *     RemoveObjectArgs.builder()
     *         .bucket("my-bucketname")
     *         .object("my-versioned-objectname")
     *         .versionId("my-versionid")
     *         .bypassRetentionMode(true)
     *         .build());
     * }</pre>
     *
     * @param args {@link RemoveObjectArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void removeObject(RemoveObjectArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> headers = HashMultimap.create();
        if (args.bypassGovernanceMode()) headers.put("x-amz-bypass-governance-retention", "true");

        Multimap<String, String> queryParams = HashMultimap.create();
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        executeDelete(args, headers, queryParams);
    }

    /**
     * Removes multiple objects lazily. Its required to iterate the returned Iterable to perform
     * removal.
     *
     * <pre>Example:{@code
     * List<DeleteObject> objects = new LinkedList<>();
     * objects.add(new DeleteObject("my-objectname1"));
     * objects.add(new DeleteObject("my-objectname2"));
     * objects.add(new DeleteObject("my-objectname3"));
     * Iterable<Result<DeleteError>> results =
     *     minioClient.removeObjects(
     *         RemoveObjectsArgs.builder().bucket("my-bucketname").objects(objects).build());
     * for (Result<DeleteError> result : results) {
     *   DeleteError error = errorResult.get();
     *   System.out.println(
     *       "Error in deleting object " + error.objectName() + "; " + error.message());
     * }
     * }</pre>
     *
     * @param args {@link RemoveObjectsArgs} object.
     * @return Iterable&ltResult&ltDeleteError&gt&gt - Lazy iterator contains object removal status.
     */
    public Iterable<Result<DeleteError>> removeObjects(RemoveObjectsArgs args) {
        checkArgs(args);

        return new Iterable<Result<DeleteError>>() {
            @Override
            public Iterator<Result<DeleteError>> iterator() {
                return new Iterator<Result<DeleteError>>() {
                    private Result<DeleteError> error;
                    private Iterator<DeleteError> errorIterator;
                    private boolean completed = false;
                    private Iterator<DeleteObject> objectIter = args.objects().iterator();

                    private synchronized void populate() {
                        List<DeleteError> errorList = null;
                        try {
                            List<DeleteObject> objectList = new LinkedList<>();
                            int i = 0;
                            while (objectIter.hasNext() && i < 1000) {
                                objectList.add(objectIter.next());
                                i++;
                            }

                            if (objectList.size() > 0) {
                                DeleteResult result =
                                        deleteObjects(
                                                args.bucket(), objectList, args.quiet(), args.bypassGovernanceMode());
                                errorList = result.errorList();
                            }
                        } catch (ErrorResponseException
                                | IllegalArgumentException
                                | InsufficientDataException
                                | InternalException
                                | InvalidBucketNameException
                                | InvalidKeyException
                                | InvalidResponseException
                                | IOException
                                | NoSuchAlgorithmException
                                | ServerException
                                | XmlParserException e) {
                            this.error = new Result<>(e);
                        } finally {
                            if (errorList != null) {
                                this.errorIterator = errorList.iterator();
                            } else {
                                this.errorIterator = new LinkedList<DeleteError>().iterator();
                            }
                        }
                    }

                    @Override
                    public boolean hasNext() {
                        if (this.completed) {
                            return false;
                        }

                        if (this.error == null && this.errorIterator == null) {
                            populate();
                        }

                        if (this.error == null && this.errorIterator != null && !this.errorIterator.hasNext()) {
                            populate();
                        }

                        if (this.error != null) {
                            return true;
                        }

                        if (this.errorIterator.hasNext()) {
                            return true;
                        }

                        this.completed = true;
                        return false;
                    }

                    @Override
                    public Result<DeleteError> next() {
                        if (this.completed) {
                            throw new NoSuchElementException();
                        }

                        if (this.error == null && this.errorIterator == null) {
                            populate();
                        }

                        if (this.error == null && this.errorIterator != null && !this.errorIterator.hasNext()) {
                            populate();
                        }

                        if (this.error != null) {
                            this.completed = true;
                            return this.error;
                        }

                        if (this.errorIterator.hasNext()) {
                            return new Result<>(this.errorIterator.next());
                        }

                        this.completed = true;
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    /**
     * Lists objects information optionally with versions of a bucket. Supports both the versions 1
     * and 2 of the S3 API. By default, the <a
     * href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html">version 2</a> API
     * is used. <br>
     * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjects.html">Version 1</a>
     * can be used by passing the optional argument {@code useVersion1} as {@code true}.
     *
     * <pre>Example:{@code
     * // Lists objects information.
     * Iterable<Result<Item>> results = minioClient.listObjects(
     *     ListObjectsArgs.builder().bucket("my-bucketname").build());
     *
     * // Lists objects information recursively.
     * Iterable<Result<Item>> results = minioClient.listObjects(
     *     ListObjectsArgs.builder().bucket("my-bucketname").recursive(true).build());
     *
     * // Lists maximum 100 objects information those names starts with 'E' and after
     * // 'ExampleGuide.pdf'.
     * Iterable<Result<Item>> results = minioClient.listObjects(
     *     ListObjectsArgs.builder()
     *         .bucket("my-bucketname")
     *         .startAfter("ExampleGuide.pdf")
     *         .prefix("E")
     *         .maxKeys(100)
     *         .build());
     *
     * // Lists maximum 100 objects information with version those names starts with 'E' and after
     * // 'ExampleGuide.pdf'.
     * Iterable<Result<Item>> results = minioClient.listObjects(
     *     ListObjectsArgs.builder()
     *         .bucket("my-bucketname")
     *         .startAfter("ExampleGuide.pdf")
     *         .prefix("E")
     *         .maxKeys(100)
     *         .includeVersions(true)
     *         .build());
     * }</pre>
     *
     * @param args Instance of {@link ListObjectsArgs} built using the builder
     * @return Iterable&lt;Result&lt;Item&gt;&gt; - Lazy iterator contains object information.
     * @throws XmlParserException upon parsing response xml
     */
    public Iterable<Result<Item>> listObjects(ListObjectsArgs args) {
        if (args.includeVersions() || args.versionIdMarker() != null) {
            return listObjectVersions(args);
        }

        if (args.useApiVersion1()) {
            return listObjectsV1(args);
        }

        return listObjectsV2(args);
    }

    private abstract class ObjectIterator implements Iterator<Result<Item>> {
        protected Result<Item> error;
        protected Iterator<? extends Item> itemIterator;
        protected Iterator<DeleteMarker> deleteMarkerIterator;
        protected Iterator<Prefix> prefixIterator;
        protected boolean completed = false;
        protected ListObjectsResult listObjectsResult;
        protected String lastObjectName;

        protected abstract void populateResult()
                throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
                InternalException, InvalidBucketNameException, InvalidKeyException,
                InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
                XmlParserException;

        protected synchronized void populate() {
            try {
                populateResult();
            } catch (ErrorResponseException
                    | IllegalArgumentException
                    | InsufficientDataException
                    | InternalException
                    | InvalidBucketNameException
                    | InvalidKeyException
                    | InvalidResponseException
                    | IOException
                    | NoSuchAlgorithmException
                    | ServerException
                    | XmlParserException e) {
                this.error = new Result<>(e);
            }

            if (this.listObjectsResult != null) {
                this.itemIterator = this.listObjectsResult.contents().iterator();
                this.deleteMarkerIterator = this.listObjectsResult.deleteMarkers().iterator();
                this.prefixIterator = this.listObjectsResult.commonPrefixes().iterator();
            } else {
                this.itemIterator = new LinkedList<Item>().iterator();
                this.deleteMarkerIterator = new LinkedList<DeleteMarker>().iterator();
                this.prefixIterator = new LinkedList<Prefix>().iterator();
            }
        }

        @Override
        public boolean hasNext() {
            if (this.completed) {
                return false;
            }

            if (this.error == null
                    && this.itemIterator == null
                    && this.deleteMarkerIterator == null
                    && this.prefixIterator == null) {
                populate();
            }

            if (this.error == null
                    && !this.itemIterator.hasNext()
                    && !this.deleteMarkerIterator.hasNext()
                    && !this.prefixIterator.hasNext()
                    && this.listObjectsResult.isTruncated()) {
                populate();
            }

            if (this.error != null) {
                return true;
            }

            if (this.itemIterator.hasNext()) {
                return true;
            }

            if (this.deleteMarkerIterator.hasNext()) {
                return true;
            }

            if (this.prefixIterator.hasNext()) {
                return true;
            }

            this.completed = true;
            return false;
        }

        @Override
        public Result<Item> next() {
            if (this.completed) {
                throw new NoSuchElementException();
            }

            if (this.error == null
                    && this.itemIterator == null
                    && this.deleteMarkerIterator == null
                    && this.prefixIterator == null) {
                populate();
            }

            if (this.error == null
                    && !this.itemIterator.hasNext()
                    && !this.deleteMarkerIterator.hasNext()
                    && !this.prefixIterator.hasNext()
                    && this.listObjectsResult.isTruncated()) {
                populate();
            }

            if (this.error != null) {
                this.completed = true;
                return this.error;
            }

            if (this.itemIterator.hasNext()) {
                Item item = this.itemIterator.next();
                this.lastObjectName = item.objectName();
                return new Result<>(item);
            }

            if (this.deleteMarkerIterator.hasNext()) {
                return new Result<>(this.deleteMarkerIterator.next());
            }

            if (this.prefixIterator.hasNext()) {
                return new Result<>(this.prefixIterator.next().toItem());
            }

            this.completed = true;
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private Iterable<Result<Item>> listObjectsV2(ListObjectsArgs args) {
        return new Iterable<Result<Item>>() {
            @Override
            public Iterator<Result<Item>> iterator() {
                return new ObjectIterator() {
                    private ListBucketResultV2 result = null;

                    @Override
                    protected void populateResult()
                            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
                            InternalException, InvalidBucketNameException, InvalidKeyException,
                            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
                            XmlParserException {
                        this.listObjectsResult = null;
                        this.itemIterator = null;
                        this.prefixIterator = null;

                        result =
                                listObjectsV2(
                                        args.bucket(),
                                        args.region(),
                                        args.delimiter(),
                                        args.useUrlEncodingType(),
                                        args.startAfter(),
                                        args.maxKeys(),
                                        args.prefix(),
                                        (result == null) ? args.continuationToken() : result.nextContinuationToken(),
                                        args.fetchOwner(),
                                        args.includeUserMetadata(),
                                        args.extraHeaders(),
                                        args.extraQueryParams());
                        this.listObjectsResult = result;
                    }
                };
            }
        };
    }

    private Iterable<Result<Item>> listObjectsV1(ListObjectsArgs args) {
        return new Iterable<Result<Item>>() {
            @Override
            public Iterator<Result<Item>> iterator() {
                return new ObjectIterator() {
                    private ListBucketResultV1 result = null;

                    @Override
                    protected void populateResult()
                            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
                            InternalException, InvalidBucketNameException, InvalidKeyException,
                            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
                            XmlParserException {
                        this.listObjectsResult = null;
                        this.itemIterator = null;
                        this.prefixIterator = null;

                        String nextMarker = (result == null) ? args.marker() : result.nextMarker();
                        if (nextMarker == null) {
                            nextMarker = this.lastObjectName;
                        }

                        result =
                                listObjectsV1(
                                        args.bucket(),
                                        args.region(),
                                        args.delimiter(),
                                        args.useUrlEncodingType(),
                                        nextMarker,
                                        args.maxKeys(),
                                        args.prefix(),
                                        args.extraHeaders(),
                                        args.extraQueryParams());
                        this.listObjectsResult = result;
                    }
                };
            }
        };
    }

    private Iterable<Result<Item>> listObjectVersions(ListObjectsArgs args) {
        return new Iterable<Result<Item>>() {
            @Override
            public Iterator<Result<Item>> iterator() {
                return new ObjectIterator() {
                    private ListVersionsResult result = null;

                    @Override
                    protected void populateResult()
                            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
                            InternalException, InvalidBucketNameException, InvalidKeyException,
                            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
                            XmlParserException {
                        this.listObjectsResult = null;
                        this.itemIterator = null;
                        this.prefixIterator = null;

                        result =
                                listObjectVersions(
                                        args.bucket(),
                                        args.region(),
                                        args.delimiter(),
                                        args.useUrlEncodingType(),
                                        (result == null) ? args.keyMarker() : result.nextKeyMarker(),
                                        args.maxKeys(),
                                        args.prefix(),
                                        (result == null) ? args.versionIdMarker() : result.nextVersionIdMarker(),
                                        args.extraHeaders(),
                                        args.extraQueryParams());
                        this.listObjectsResult = result;
                    }
                };
            }
        };
    }

    /**
     * Lists bucket information of all buckets.
     *
     * <pre>Example:{@code
     * List<Bucket> bucketList = minioClient.listBuckets();
     * for (Bucket bucket : bucketList) {
     *   System.out.println(bucket.creationDate() + ", " + bucket.name());
     * }
     * }</pre>
     *
     * @return List&ltBucket&gt - List of bucket information.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public List<Bucket> listBuckets()
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        try (Response response =
                     execute(
                             Method.GET, null, null, (region != null) ? region : US_EAST_1, null, null, null, 0)) {
            ListAllMyBucketsResult result =
                    Xml.unmarshal(ListAllMyBucketsResult.class, response.body().charStream());
            return result.buckets();
        }
    }


    /**
     * Checks if a bucket exists.
     *
     * <pre>Example:{@code
     * boolean found =
     *      minioClient.bucketExists(BucketExistsArgs.builder().bucket("my-bucketname").build());
     * if (found) {
     *   System.out.println("my-bucketname exists");
     * } else {
     *   System.out.println("my-bucketname does not exist");
     * }
     * }</pre>
     *
     * @param args {@link BucketExistsArgs} object.
     * @return boolean - True if the bucket exists.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public boolean bucketExists(BucketExistsArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        try {
            executeHead(args, null, null);
            return true;
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.NO_SUCH_BUCKET) {
                throw e;
            }
        }
        return false;
    }

    /**
     * Creates a bucket with region and object lock.
     *
     * <pre>Example:{@code
     * // Create bucket with default region.
     * minioClient.makeBucket(
     *     MakeBucketArgs.builder()
     *         .bucket("my-bucketname")
     *         .build());
     *
     * // Create bucket with specific region.
     * minioClient.makeBucket(
     *     MakeBucketArgs.builder()
     *         .bucket("my-bucketname")
     *         .region("us-west-1")
     *         .build());
     *
     * // Create object-lock enabled bucket with specific region.
     * minioClient.makeBucket(
     *     MakeBucketArgs.builder()
     *         .bucket("my-bucketname")
     *         .region("us-west-1")
     *         .objectLock(true)
     *         .build());
     * }</pre>
     *
     * @param args Object with bucket name, region and lock functionality
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws RegionConflictException    thrown to indicate passed region conflict with default region.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void makeBucket(MakeBucketArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, RegionConflictException,
            ServerException, XmlParserException {
        checkArgs(args);

        String region = args.region();
        if (this.region != null && !this.region.isEmpty()) {
            // Error out if region does not match with region passed via constructor.
            if (region != null && !region.equals(this.region)) {
                throw new IllegalArgumentException(
                        "region must be " + this.region + ", but passed " + region);
            }

            region = this.region;
        }

        if (region == null) {
            region = US_EAST_1;
        }

        CreateBucketConfiguration config = null;
        if (!region.equals(US_EAST_1)) {
            config = new CreateBucketConfiguration(region);
        }

        Multimap<String, String> headers = null;
        if (args.objectLock()) {
            headers = HashMultimap.create();
            headers.put("x-amz-bucket-object-lock-enabled", "true");
        }

        try (Response response =
                     execute(
                             Method.PUT,
                             args.bucket(),
                             null,
                             region,
                             merge(args.extraHeaders(), headers),
                             args.extraQueryParams(),
                             config,
                             0)) {

        }
    }


    /**
     * Enables object versioning feature in a bucket.
     *
     * <pre>Example:{@code
     * minioClient.enableVersioning(EnableVersioningArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link EnableVersioningArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void enableVersioning(EnableVersioningArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("versioning", "");

        Response response = executePut(args, null, queryParams, new VersioningConfiguration(true), 0);
        response.close();
    }


    /**
     * Disables object versioning feature in a bucket.
     *
     * <pre>Example:{@code
     * minioClient.disableVersioning(
     *     DisableVersioningArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link DisableVersioningArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void disableVersioning(DisableVersioningArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("versioning", "");

        Response response = executePut(args, null, queryParams, new VersioningConfiguration(false), 0);
        response.close();
    }

    /**
     * Returns true if versioning is enabled on the bucket.
     *
     * <pre>Example:{@code
     * boolean isVersioningEnabled =
     *  minioClient.isVersioningEnabled(
     *       IsVersioningEnabledArgs.builder().bucket("my-bucketname").build());
     * if (isVersioningEnabled) {
     *   System.out.println("Bucket versioning is enabled");
     * } else {
     *   System.out.println("Bucket versioning is disabled");
     * }
     * }</pre>
     *
     * @param args {@link IsVersioningEnabledArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public boolean isVersioningEnabled(IsVersioningEnabledArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("versioning", "");

        try (Response response = executeGet(args, null, queryParams)) {
            VersioningConfiguration result =
                    Xml.unmarshal(VersioningConfiguration.class, response.body().charStream());
            return result.status();
        }
    }

    /**
     * Sets default object retention in a bucket.
     *
     * <pre>Example:{@code
     * ObjectLockConfiguration config = new ObjectLockConfiguration(
     *     RetentionMode.COMPLIANCE, new RetentionDurationDays(100));
     * minioClient.setDefaultRetention(
     *     SetDefaultRetentionArgs.builder().bucket("my-bucketname").config(config).build());
     * }</pre>
     *
     * @param args {@link SetDefaultRetentionArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setDefaultRetention(SetDefaultRetentionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("object-lock", "");

        Response response = executePut(args, null, queryParams, args.config(), 0);
        response.close();
    }

    /**
     * Deletes default object retention in a bucket.
     *
     * <pre>Example:{@code
     * minioClient.deleteDefaultRetention(
     *     DeleteDefaultRetentionArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link DeleteDefaultRetentionArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void deleteDefaultRetention(DeleteDefaultRetentionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("object-lock", "");

        Response response = executePut(args, null, queryParams, new ObjectLockConfiguration(), 0);
        response.close();
    }


    /**
     * Gets default object retention in a bucket.
     *
     * <pre>Example:{@code
     * ObjectLockConfiguration config =
     *     minioClient.getDefaultRetention(
     *         GetDefaultRetentionArgs.builder().bucket("my-bucketname").build());
     * System.out.println("Mode: " + config.mode());
     * System.out.println(
     *     "Duration: " + config.duration().duration() + " " + config.duration().unit());
     * }</pre>
     *
     * @param args {@link GetDefaultRetentionArgs} object.
     * @return {@link ObjectLockConfiguration} - Default retention configuration.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public ObjectLockConfiguration getDefaultRetention(GetDefaultRetentionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("object-lock", "");

        try (Response response = executeGet(args, null, queryParams)) {
            return Xml.unmarshal(ObjectLockConfiguration.class, response.body().charStream());
        }
    }

    /**
     * Sets retention configuration to an object.
     *
     * <pre>Example:{@code
     *  Retention retention = new Retention(
     *       RetentionMode.COMPLIANCE, ZonedDateTime.now().plusYears(1));
     *  minioClient.setObjectRetention(
     *      SetObjectRetentionArgs.builder()
     *          .bucket("my-bucketname")
     *          .object("my-objectname")
     *          .config(config)
     *          .bypassGovernanceMode(true)
     *          .build());
     * }</pre>
     *
     * @param args {@link SetObjectRetentionArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setObjectRetention(SetObjectRetentionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("retention", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        Multimap<String, String> headers = HashMultimap.create();
        if (args.bypassGovernanceMode()) headers.put("x-amz-bypass-governance-retention", "True");

        Response response = executePut(args, headers, queryParams, args.config(), 0);
        response.close();
    }

    /**
     * Gets retention configuration of an object.
     *
     * <pre>Example:{@code
     * Retention retention =
     *     minioClient.getObjectRetention(GetObjectRetentionArgs.builder()
     *        .bucket(bucketName)
     *        .object(objectName)
     *        .versionId(versionId)
     *        .build()););
     * System.out.println(
     *     "mode: " + retention.mode() + "until: " + retention.retainUntilDate());
     * }</pre>
     *
     * @param args {@link GetObjectRetentionArgs} object.
     * @return {@link Retention} - Object retention configuration.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public Retention getObjectRetention(GetObjectRetentionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("retention", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        try (Response response = executeGet(args, null, queryParams)) {
            return Xml.unmarshal(Retention.class, response.body().charStream());
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.NO_SUCH_OBJECT_LOCK_CONFIGURATION) {
                throw e;
            }
        }
        return null;
    }


    /**
     * Enables legal hold on an object.
     *
     * <pre>Example:{@code
     * minioClient.enableObjectLegalHold(
     *    EnableObjectLegalHoldArgs.builder()
     *        .bucket("my-bucketname")
     *        .object("my-objectname")
     *        .versionId("object-versionId")
     *        .build());
     * }</pre>
     *
     * @param args {@link EnableObjectLegalHoldArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void enableObjectLegalHold(EnableObjectLegalHoldArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("legal-hold", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        Response response = executePut(args, null, queryParams, new LegalHold(true), 0);
        response.close();
    }


    /**
     * Disables legal hold on an object.
     *
     * <pre>Example:{@code
     * minioClient.disableObjectLegalHold(
     *    DisableObjectLegalHoldArgs.builder()
     *        .bucket("my-bucketname")
     *        .object("my-objectname")
     *        .versionId("object-versionId")
     *        .build());
     * }</pre>
     *
     * @param args {@link DisableObjectLegalHoldArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void disableObjectLegalHold(DisableObjectLegalHoldArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("legal-hold", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        Response response = executePut(args, null, queryParams, new LegalHold(false), 0);
        response.close();
    }

    /**
     * Returns true if legal hold is enabled on an object.
     *
     * <pre>Example:{@code
     * boolean status =
     *     s3Client.isObjectLegalHoldEnabled(
     *        IsObjectLegalHoldEnabledArgs.builder()
     *             .bucket("my-bucketname")
     *             .object("my-objectname")
     *             .versionId("object-versionId")
     *             .build());
     * if (status) {
     *   System.out.println("Legal hold is on");
     *  } else {
     *   System.out.println("Legal hold is off");
     *  }
     * }</pre>
     * <p>
     * args {@link IsObjectLegalHoldEnabledArgs} object.
     *
     * @return boolean - True if legal hold is enabled.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public boolean isObjectLegalHoldEnabled(IsObjectLegalHoldEnabledArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("legal-hold", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        try (Response response = executeGet(args, null, queryParams)) {
            LegalHold result = Xml.unmarshal(LegalHold.class, response.body().charStream());
            return result.status();
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.NO_SUCH_OBJECT_LOCK_CONFIGURATION) {
                throw e;
            }
        }
        return false;
    }

    /**
     * Removes an empty bucket using arguments
     *
     * <pre>Example:{@code
     * minioClient.removeBucket(RemoveBucketArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link RemoveBucketArgs} bucket.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void removeBucket(RemoveBucketArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);
        executeDelete(args, null, null);
    }

    public ObjectWriteResponse putObject(
            ObjectWriteArgs args,
            Object data,
            long objectSize,
            long partSize,
            int partCount,
            String contentType)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        Multimap<String, String> headers = HashMultimap.create();
        headers.putAll(args.extraHeaders());
        headers.putAll(args.genHeaders());
        if (!headers.containsKey("Content-Type")) {
            headers.put("Content-Type", contentType);
        }

        String uploadId = null;
        long uploadedSize = 0L;
        Part[] parts = null;
        log.info("partCount:{}", partCount);
        try {
            for (int partNumber = 1; partNumber <= partCount || partCount < 0; partNumber++) {
                long availableSize = partSize;
                if (partCount > 0) {
                    if (partNumber == partCount) {
                        availableSize = objectSize - uploadedSize;
                    }
                } else {
                    availableSize = getAvailableSize(data, partSize + 1);

                    // If availableSize is less or equal to partSize, then we have reached last
                    // part.
                    if (availableSize <= partSize) {
                        partCount = partNumber;
                    } else {
                        availableSize = partSize;
                    }
                }

                if (partCount == 1) {
                    return putObject(
                            args.bucket(),
                            args.region(),
                            args.object(),
                            data,
                            (int) availableSize,
                            headers,
                            args.extraQueryParams());
                }

                if (uploadId == null) {
                    uploadId =
                            createMultipartUpload(
                                    args.bucket(), args.region(), args.object(), headers, args.extraQueryParams());
                    parts = new Part[ObjectWriteArgs.MAX_MULTIPART_COUNT];
                    log.info("uploadId:{}, region:{},object:{},headers:{},extraQueryParams:{}", uploadId, args.region(),
                            args.object(), headers, args.extraQueryParams());
                }

                Map<String, String> ssecHeaders = null;
                // set encryption headers in the case of SSE-C.
                if (args.sse() != null && args.sse().type() == ServerSideEncryption.Type.SSE_C) {
                    ssecHeaders = args.sse().headers();
                }

                String etag =
                        uploadPart(
                                args.bucket(),
                                args.object(),
                                data,
                                (int) availableSize,
                                uploadId,
                                partNumber,
                                ssecHeaders);
                log.info("etag:{},availableSize:{}, ssecHeaders:{}", etag, availableSize, ssecHeaders);
                parts[partNumber - 1] = new Part(partNumber, etag);
                uploadedSize += availableSize;
            }
            log.info("------completeMultipartUpload");
            return completeMultipartUpload(
                    args.bucket(), args.region(), args.object(), uploadId, parts, null, null);
        } catch (RuntimeException e) {
            if (uploadId != null) {
                abortMultipartUpload(args.bucket(), args.object(), uploadId);
            }
            throw e;
        } catch (Exception e) {
            if (uploadId != null) {
                abortMultipartUpload(args.bucket(), args.object(), uploadId);
            }
            throw e;
        }
    }


    /**
     * Uploads data from a stream to an object.
     *
     * <pre>Example:{@code
     * // Upload known sized input stream.
     * minioClient.putObject(
     *     PutObjectArgs.builder().bucket("my-bucketname").object("my-objectname").stream(
     *             inputStream, size, -1)
     *         .contentType("video/mp4")
     *         .build());
     *
     * // Upload unknown sized input stream.
     * minioClient.putObject(
     *     PutObjectArgs.builder().bucket("my-bucketname").object("my-objectname").stream(
     *             inputStream, -1, 10485760)
     *         .contentType("video/mp4")
     *         .build());
     *
     * // Create object ends with '/' (also called as folder or directory).
     * minioClient.putObject(
     *     PutObjectArgs.builder().bucket("my-bucketname").object("path/to/").stream(
     *             new ByteArrayInputStream(new byte[] {}), 0, -1)
     *         .build());
     *
     * // Upload input stream with headers and user metadata.
     * Map<String, String> headers = new HashMap<>();
     * headers.put("X-Amz-Storage-Class", "REDUCED_REDUNDANCY");
     * Map<String, String> userMetadata = new HashMap<>();
     * userMetadata.put("My-Project", "Project One");
     * minioClient.putObject(
     *     PutObjectArgs.builder().bucket("my-bucketname").object("my-objectname").stream(
     *             inputStream, size, -1)
     *         .headers(headers)
     *         .userMetadata(userMetadata)
     *         .build());
     *
     * // Upload input stream with server-side encryption.
     * minioClient.putObject(
     *     PutObjectArgs.builder().bucket("my-bucketname").object("my-objectname").stream(
     *             inputStream, size, -1)
     *         .sse(sse)
     *         .build());
     * }</pre>
     *
     * @param args {@link PutObjectArgs} object.
     * @return {@link ObjectWriteResponse} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public ObjectWriteResponse putObject(PutObjectArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);
        // TODO
        // args.validateSse(this.baseUrl);
        return putObject(
                args,
                args.stream(),
                args.objectSize(),
                args.partSize(),
                args.partCount(),
                args.contentType());
    }

    /**
     * Uploads data from a file to an object.
     *
     * <pre>Example:{@code
     * // Upload an JSON file.
     * minioClient.uploadObject(
     *     UploadObjectArgs.builder()
     *         .bucket("my-bucketname").object("my-objectname").filename("person.json").build());
     *
     * // Upload a video file.
     * minioClient.uploadObject(
     *     UploadObjectArgs.builder()
     *         .bucket("my-bucketname")
     *         .object("my-objectname")
     *         .filename("my-video.avi")
     *         .contentType("video/mp4")
     *         .build());
     * }</pre>
     *
     * @param args {@link UploadObjectArgs} object.
     * @return {@link ObjectWriteResponse} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public ObjectWriteResponse uploadObject(UploadObjectArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);
        // TODO
        // args.validateSse(this.baseUrl);
        try (RandomAccessFile file = new RandomAccessFile(args.filename(), "r")) {
            return putObject(
                    args, file, args.objectSize(), args.partSize(), args.partCount(), args.contentType());
        }
    }


    /**
     * Gets bucket policy configuration of a bucket.
     *
     * <pre>Example:{@code
     * String config =
     *     minioClient.getBucketPolicy(GetBucketPolicyArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link GetBucketPolicyArgs} object.
     * @return String - Bucket policy configuration as JSON string.
     * @throws BucketPolicyTooLargeException thrown to indicate returned bucket policy is too large.
     * @throws ErrorResponseException        thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException      throws to indicate invalid argument passed.
     * @throws InsufficientDataException     thrown to indicate not enough data available in InputStream.
     * @throws InternalException             thrown to indicate internal library error.
     * @throws InvalidBucketNameException    thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException           thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException      thrown to indicate S3 service returned invalid or no error
     *                                       response.
     * @throws IOException                   thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException      thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException            thrown to indicate XML parsing error.
     */
    public String getBucketPolicy(GetBucketPolicyArgs args)
            throws BucketPolicyTooLargeException, ErrorResponseException, IllegalArgumentException,
            InsufficientDataException, InternalException, InvalidBucketNameException,
            InvalidKeyException, InvalidResponseException, IOException, NoSuchAlgorithmException,
            ServerException, XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("policy", "");

        try (Response response = executeGet(args, null, queryParams)) {
            byte[] buf = new byte[MAX_BUCKET_POLICY_SIZE];
            int bytesRead = 0;
            bytesRead = response.body().byteStream().read(buf, 0, MAX_BUCKET_POLICY_SIZE);
            if (bytesRead < 0) {
                throw new IOException("unexpected EOF when reading bucket policy");
            }

            // Read one byte extra to ensure only MAX_BUCKET_POLICY_SIZE data is sent by the server.
            if (bytesRead == MAX_BUCKET_POLICY_SIZE) {
                int byteRead = 0;
                while (byteRead == 0) {
                    byteRead = response.body().byteStream().read();
                    if (byteRead < 0) {
                        break; // reached EOF which is fine.
                    }

                    if (byteRead > 0) {
                        throw new BucketPolicyTooLargeException(args.bucket());
                    }
                }
            }

            return new String(buf, 0, bytesRead, StandardCharsets.UTF_8);
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.NO_SUCH_BUCKET_POLICY) {
                throw e;
            }
        }

        return "";
    }

    /**
     * Sets bucket policy configuration to a bucket.
     *
     * <pre>Example:{@code
     * // Assume policyJson contains below JSON string;
     * // {
     * //     "Statement": [
     * //         {
     * //             "Action": [
     * //                 "s3:GetBucketLocation",
     * //                 "s3:ListBucket"
     * //             ],
     * //             "Effect": "Allow",
     * //             "Principal": "*",
     * //             "Resource": "arn:aws:s3:::my-bucketname"
     * //         },
     * //         {
     * //             "Action": "s3:GetObject",
     * //             "Effect": "Allow",
     * //             "Principal": "*",
     * //             "Resource": "arn:aws:s3:::my-bucketname/myobject*"
     * //         }
     * //     ],
     * //     "Version": "2012-10-17"
     * // }
     * //
     * minioClient.setBucketPolicy(
     *     SetBucketPolicyArgs.builder().bucket("my-bucketname").config(policyJson).build());
     * }</pre>
     *
     * @param args {@link SetBucketPolicyArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setBucketPolicy(SetBucketPolicyArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("policy", "");

        Multimap<String, String> headers = HashMultimap.create();
        headers.put("Content-Type", "application/json");

        Response response = executePut(args, headers, queryParams, args.config(), 0);
        response.close();
    }

    /**
     * Deletes bucket policy configuration to a bucket.
     *
     * <pre>Example:{@code
     * minioClient.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket("my-bucketname"));
     * }</pre>
     *
     * @param args {@link DeleteBucketPolicyArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void deleteBucketPolicy(DeleteBucketPolicyArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("policy", "");

        try {
            executeDelete(args, null, queryParams);
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.NO_SUCH_BUCKET_POLICY) {
                throw e;
            }
        }
    }

    /**
     * Sets life-cycle configuration to a bucket.
     *
     * <pre>Example:{@code
     * // Lets consider variable 'lifeCycleXml' contains below XML String;
     * // <LifecycleConfiguration>
     * //   <Rule>
     * //     <ID>expire-bucket</ID>
     * //     <Prefix></Prefix>
     * //     <Status>Enabled</Status>
     * //     <Expiration>
     * //       <Days>365</Days>
     * //     </Expiration>
     * //   </Rule>
     * // </LifecycleConfiguration>
     * //
     * minioClient.setBucketLifeCycle(
     *     SetBucketLifeCycleArgs.builder().bucket("my-bucketname").config(lifeCycleXml).build());
     * }</pre>
     *
     * @param args {@link SetBucketLifeCycleArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setBucketLifeCycle(SetBucketLifeCycleArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("lifecycle", "");

        Response response = executePut(args, null, queryParams, args.config(), 0);
        response.close();
    }


    /**
     * Deletes life-cycle configuration of a bucket.
     *
     * <pre>Example:{@code
     * deleteBucketLifeCycle(DeleteBucketLifeCycleArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link DeleteBucketLifeCycleArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void deleteBucketLifeCycle(DeleteBucketLifeCycleArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("lifecycle", "");

        executeDelete(args, null, queryParams);
    }

    /**
     * Gets life-cycle configuration of a bucket.
     *
     * <pre>Example:{@code
     * String lifecycle =
     *     minioClient.getBucketLifeCycle(
     *         GetBucketLifeCycleArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link GetBucketLifeCycleArgs} object.
     * @return String - Life cycle configuration as XML string.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public String getBucketLifeCycle(GetBucketLifeCycleArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("lifecycle", "");

        try (Response response = executeGet(args, null, queryParams)) {
            return new String(response.body().bytes(), StandardCharsets.UTF_8);
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.NO_SUCH_LIFECYCLE_CONFIGURATION) {
                throw e;
            }
        }

        return "";
    }

    /**
     * Gets notification configuration of a bucket.
     *
     * <pre>Example:{@code
     * NotificationConfiguration config =
     *     minioClient.getBucketNotification(
     *         GetBucketNotificationArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link GetBucketNotificationArgs} object.
     * @return {@link NotificationConfiguration} - Notification configuration.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public NotificationConfiguration getBucketNotification(GetBucketNotificationArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("notification", "");

        try (Response response = executeGet(args, null, queryParams)) {
            return Xml.unmarshal(NotificationConfiguration.class, response.body().charStream());
        }
    }

    /**
     * Sets notification configuration to a bucket.
     *
     * <pre>Example:{@code
     * List<EventType> eventList = new LinkedList<>();
     * eventList.add(EventType.OBJECT_CREATED_PUT);
     * eventList.add(EventType.OBJECT_CREATED_COPY);
     *
     * QueueConfiguration queueConfiguration = new QueueConfiguration();
     * queueConfiguration.setQueue("arn:minio:sqs::1:webhook");
     * queueConfiguration.setEvents(eventList);
     * queueConfiguration.setPrefixRule("images");
     * queueConfiguration.setSuffixRule("pg");
     *
     * List<QueueConfiguration> queueConfigurationList = new LinkedList<>();
     * queueConfigurationList.add(queueConfiguration);
     *
     * NotificationConfiguration config = new NotificationConfiguration();
     * config.setQueueConfigurationList(queueConfigurationList);
     *
     * minioClient.setBucketNotification(
     *     SetBucketNotificationArgs.builder().bucket("my-bucketname").config(config).build());
     * }</pre>
     *
     * @param args {@link SetBucketNotificationArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setBucketNotification(SetBucketNotificationArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("notification", "");
        Response response = executePut(args, null, queryParams, args.config(), 0);
        response.close();
    }

    /**
     * Deletes notification configuration of a bucket.
     *
     * <pre>Example:{@code
     * minioClient.deleteBucketNotification(
     *     DeleteBucketNotificationArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link DeleteBucketNotificationArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void deleteBucketNotification(DeleteBucketNotificationArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("notification", "");
        Response response = executePut(args, null, queryParams, new NotificationConfiguration(), 0);
        response.close();
    }

    /**
     * Lists incomplete object upload information of a bucket for prefix recursively.
     *
     * <pre>Example:{@code
     *  // Lists incomplete object upload information of a bucket.
     *   Iterable<Result<Upload>> results =
     *       minioClient.listIncompleteUploads(
     *           ListIncompleteUploadsArgs.builder().bucket("my-bucketname").build());
     *   for (Result<Upload> result : results) {
     *     Upload upload = result.get();
     *     System.out.println(upload.uploadId() + ", " + upload.objectName());
     *   }
     *
     *   // Lists incomplete object upload information of a bucket for prefix.
     *   Iterable<Result<Upload>> results =
     *       minioClient.listIncompleteUploads(
     *           ListIncompleteUploadsArgs.builder()
     *               .bucket("my-bucketname")
     *               .prefix("my-obj")
     *               .build());
     *   for (Result<Upload> result : results) {
     *     Upload upload = result.get();
     *     System.out.println(upload.uploadId() + ", " + upload.objectName());
     *   }
     *
     *   // Lists incomplete object upload information of a bucket for prefix recursively.
     *   Iterable<Result<Upload>> results =
     *       minioClient.listIncompleteUploads(
     *           ListIncompleteUploadsArgs.builder()
     *               .bucket("my-bucketname")
     *               .prefix("my-obj")
     *               .recursive(true)
     *               .build());
     *   for (Result<Upload> result : results) {
     *    Upload upload = result.get();
     *    System.out.println(upload.uploadId() + ", " + upload.objectName());
     *   }
     *
     *   // Lists incomplete object upload information of a bucket for prefix, delimiter.
     *   //  keyMarker, uploadIdMarker and maxUpload to 500
     *   Iterable<Result<Upload>> results =
     *       minioClient.listIncompleteUploads(
     *           ListIncompleteUploadsArgs.builder()
     *               .bucket("my-bucketname")
     *               .prefix("my-obj")
     *               .delimiter("-")
     *               .keyMarker("b")
     *               .maxUploads(500)
     *               .uploadIdMarker("k")
     *               .build());
     *   for (Result<Upload> result : results) {
     *    Upload upload = result.get();
     *    System.out.println(upload.uploadId() + ", " + upload.objectName());
     *   }
     * }</pre>
     *
     * @param args {@link ListIncompleteUploadsArgs} objects.
     * @return Iterable&lt;Result&lt;Upload&gt;&gt; - Lazy iterator contains object upload
     * information.
     */
    public Iterable<Result<Upload>> listIncompleteUploads(ListIncompleteUploadsArgs args) {
        checkArgs(args);
        return this.listIncompleteUploads(args, true);
    }

    /**
     * Returns Iterable<Result<Upload>> of given ListIncompleteUploadsArgs argumentsr. All parts size
     * are aggregated when aggregatePartSize is true.
     */
    private Iterable<Result<Upload>> listIncompleteUploads(
            ListIncompleteUploadsArgs args, final boolean aggregatePartSize) {
        return new Iterable<Result<Upload>>() {
            @Override
            public Iterator<Result<Upload>> iterator() {
                return new Iterator<Result<Upload>>() {
                    private String nextKeyMarker = args.keyMarker();
                    private String nextUploadIdMarker = args.uploadIdMarker();
                    private ListMultipartUploadsResult listMultipartUploadsResult;
                    private Result<Upload> error;
                    private Iterator<Upload> uploadIterator;
                    private boolean completed = false;

                    private synchronized void populate() {
                        String delimiter = args.delimiter();
                        if (args.recursive()) {
                            delimiter = null;
                        }

                        this.listMultipartUploadsResult = null;
                        this.uploadIterator = null;

                        try {
                            this.listMultipartUploadsResult =
                                    listMultipartUploads(
                                            args.bucket(),
                                            delimiter,
                                            nextKeyMarker,
                                            args.maxUploads(),
                                            args.prefix(),
                                            nextUploadIdMarker);
                        } catch (ErrorResponseException
                                | IllegalArgumentException
                                | InsufficientDataException
                                | InternalException
                                | InvalidBucketNameException
                                | InvalidKeyException
                                | InvalidResponseException
                                | IOException
                                | NoSuchAlgorithmException
                                | ServerException
                                | XmlParserException e) {
                            this.error = new Result<>(e);
                        } finally {
                            if (this.listMultipartUploadsResult != null) {
                                this.uploadIterator = this.listMultipartUploadsResult.uploads().iterator();
                            } else {
                                this.uploadIterator = new LinkedList<Upload>().iterator();
                            }
                        }
                    }

                    private synchronized long getAggregatedPartSize(String objectName, String uploadId)
                            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
                            InternalException, InvalidBucketNameException, InvalidKeyException,
                            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
                            XmlParserException {
                        long aggregatedPartSize = 0;

                        for (Result<Part> result : listObjectParts(args.bucket(), objectName, uploadId)) {
                            aggregatedPartSize += result.get().partSize();
                        }

                        return aggregatedPartSize;
                    }

                    @Override
                    public boolean hasNext() {
                        if (this.completed) {
                            return false;
                        }

                        if (this.error == null && this.uploadIterator == null) {
                            populate();
                        }

                        if (this.error == null
                                && !this.uploadIterator.hasNext()
                                && this.listMultipartUploadsResult.isTruncated()) {
                            this.nextKeyMarker = this.listMultipartUploadsResult.nextKeyMarker();
                            this.nextUploadIdMarker = this.listMultipartUploadsResult.nextUploadIdMarker();
                            populate();
                        }

                        if (this.error != null) {
                            return true;
                        }

                        if (this.uploadIterator.hasNext()) {
                            return true;
                        }

                        this.completed = true;
                        return false;
                    }

                    @Override
                    public Result<Upload> next() {
                        if (this.completed) {
                            throw new NoSuchElementException();
                        }

                        if (this.error == null && this.uploadIterator == null) {
                            populate();
                        }

                        if (this.error == null
                                && !this.uploadIterator.hasNext()
                                && this.listMultipartUploadsResult.isTruncated()) {
                            this.nextKeyMarker = this.listMultipartUploadsResult.nextKeyMarker();
                            this.nextUploadIdMarker = this.listMultipartUploadsResult.nextUploadIdMarker();
                            populate();
                        }

                        if (this.error != null) {
                            this.completed = true;
                            return this.error;
                        }

                        if (this.uploadIterator.hasNext()) {
                            Upload upload = this.uploadIterator.next();

                            if (aggregatePartSize) {
                                long aggregatedPartSize;

                                try {
                                    aggregatedPartSize =
                                            getAggregatedPartSize(upload.objectName(), upload.uploadId());
                                } catch (ErrorResponseException
                                        | IllegalArgumentException
                                        | InsufficientDataException
                                        | InternalException
                                        | InvalidBucketNameException
                                        | InvalidKeyException
                                        | InvalidResponseException
                                        | IOException
                                        | NoSuchAlgorithmException
                                        | ServerException
                                        | XmlParserException e) {
                                    // special case: ignore the error as we can't propagate the exception in next()
                                    aggregatedPartSize = -1;
                                }

                                upload.setAggregatedPartSize(aggregatedPartSize);
                            }

                            return new Result<>(upload);
                        }

                        this.completed = true;
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    /**
     * Executes List object parts of multipart upload for given bucket name, object name and upload ID
     * and returns Iterable<Result<Part>>.
     */
    private Iterable<Result<Part>> listObjectParts(
            final String bucketName, final String objectName, final String uploadId) {
        return new Iterable<Result<Part>>() {
            @Override
            public Iterator<Result<Part>> iterator() {
                return new Iterator<Result<Part>>() {
                    private int nextPartNumberMarker;
                    private ListPartsResult listPartsResult;
                    private Result<Part> error;
                    private Iterator<Part> partIterator;
                    private boolean completed = false;

                    private synchronized void populate() {
                        this.listPartsResult = null;
                        this.partIterator = null;

                        try {
                            this.listPartsResult =
                                    listParts(bucketName, objectName, null, nextPartNumberMarker, uploadId);
                        } catch (ErrorResponseException
                                | IllegalArgumentException
                                | InsufficientDataException
                                | InternalException
                                | InvalidBucketNameException
                                | InvalidKeyException
                                | InvalidResponseException
                                | IOException
                                | NoSuchAlgorithmException
                                | ServerException
                                | XmlParserException e) {
                            this.error = new Result<>(e);
                        } finally {
                            if (this.listPartsResult != null) {
                                this.partIterator = this.listPartsResult.partList().iterator();
                            } else {
                                this.partIterator = new LinkedList<Part>().iterator();
                            }
                        }
                    }

                    @Override
                    public boolean hasNext() {
                        if (this.completed) {
                            return false;
                        }

                        if (this.error == null && this.partIterator == null) {
                            populate();
                        }

                        if (this.error == null
                                && !this.partIterator.hasNext()
                                && this.listPartsResult.isTruncated()) {
                            this.nextPartNumberMarker = this.listPartsResult.nextPartNumberMarker();
                            populate();
                        }

                        if (this.error != null) {
                            return true;
                        }

                        if (this.partIterator.hasNext()) {
                            return true;
                        }

                        this.completed = true;
                        return false;
                    }

                    @Override
                    public Result<Part> next() {
                        if (this.completed) {
                            throw new NoSuchElementException();
                        }

                        if (this.error == null && this.partIterator == null) {
                            populate();
                        }

                        if (this.error == null
                                && !this.partIterator.hasNext()
                                && this.listPartsResult.isTruncated()) {
                            this.nextPartNumberMarker = this.listPartsResult.nextPartNumberMarker();
                            populate();
                        }

                        if (this.error != null) {
                            this.completed = true;
                            return this.error;
                        }

                        if (this.partIterator.hasNext()) {
                            return new Result<>(this.partIterator.next());
                        }

                        this.completed = true;
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    /**
     * Removes incomplete uploads of an object.
     *
     * <pre>Example:{@code
     * minioClient.removeIncompleteUpload(
     *     RemoveIncompleteUploadArgs.builder()
     *     .bucket("my-bucketname")
     *     .object("my-objectname")
     *     .build());
     * }</pre>
     *
     * @param args instance of {@link RemoveIncompleteUploadArgs}
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void removeIncompleteUpload(RemoveIncompleteUploadArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        for (Result<Upload> r :
                listIncompleteUploads(
                        ListIncompleteUploadsArgs.builder()
                                .bucket(args.bucket())
                                .prefix(args.object())
                                .recursive(true)
                                .build(),
                        false)) {

            // args.bucket(), args.object(), true, false)) {
            Upload upload = r.get();
            if (args.object().equals(upload.objectName())) {
                abortMultipartUpload(args.bucket(), args.object(), upload.uploadId());
                return;
            }
        }
    }

    /**
     * Listens events of object prefix and suffix of a bucket. The returned closable iterator is
     * lazily evaluated hence its required to iterate to get new records and must be used with
     * try-with-resource to release underneath network resources.
     *
     * <pre>Example:{@code
     * String[] events = {"s3:ObjectCreated:*", "s3:ObjectAccessed:*"};
     * try (CloseableIterator<Result<NotificationRecords>> ci =
     *     minioClient.listenBucketNotification(
     *         ListenBucketNotificationArgs.builder()
     *             .bucket("bucketName")
     *             .prefix("")
     *             .suffix("")
     *             .events(events)
     *             .build())) {
     *   while (ci.hasNext()) {
     *     NotificationRecords records = ci.next().get();
     *     for (Event event : records.events()) {
     *       System.out.println("Event " + event.eventType() + " occurred at "
     *           + event.eventTime() + " for " + event.bucketName() + "/"
     *           + event.objectName());
     *     }
     *   }
     * }
     * }</pre>
     *
     * @param args {@link ListenBucketNotificationArgs} object.
     * @return CloseableIterator&ltResult&ltNotificationRecords&gt&gt - Lazy closable iterator
     * contains event records.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public CloseableIterator<Result<NotificationRecords>> listenBucketNotification(
            ListenBucketNotificationArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("prefix", args.prefix());
        queryParams.put("suffix", args.suffix());
        for (String event : args.events()) {
            queryParams.put("events", event);
        }

        Response response = executeGet(args, null, queryParams);
        NotificationResultRecords result = new NotificationResultRecords(response);
        return result.closeableIterator();
    }

    /**
     * Selects content of an object by SQL expression.
     *
     * <pre>Example:{@code
     * String sqlExpression = "select * from S3Object";
     * InputSerialization is =
     *     new InputSerialization(null, false, null, null, FileHeaderInfo.USE, null, null,
     *         null);
     * OutputSerialization os =
     *     new OutputSerialization(null, null, null, QuoteFields.ASNEEDED, null);
     * SelectResponseStream stream =
     *     minioClient.selectObjectContent(
     *       SelectObjectContentArgs.builder()
     *       .bucket("my-bucketname")
     *       .object("my-objectname")
     *       .sqlExpression(sqlExpression)
     *       .inputSerialization(is)
     *       .outputSerialization(os)
     *       .requestProgress(true)
     *       .build());
     *
     * byte[] buf = new byte[512];
     * int bytesRead = stream.read(buf, 0, buf.length);
     * System.out.println(new String(buf, 0, bytesRead, StandardCharsets.UTF_8));
     *
     * Stats stats = stream.stats();
     * System.out.println("bytes scanned: " + stats.bytesScanned());
     * System.out.println("bytes processed: " + stats.bytesProcessed());
     * System.out.println("bytes returned: " + stats.bytesReturned());
     *
     * stream.close();
     * }</pre>
     *
     * @param args instance of {@link SelectObjectContentArgs}
     * @return {@link SelectResponseStream} - Contains filtered records and progress.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public SelectResponseStream selectObjectContent(SelectObjectContentArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);
        // TODO
        // args.validateSsec(this.baseUrl);

        Multimap<String, String> headers = null;
        if (args.ssec() != null) {
            headers = Multimaps.forMap(args.ssec().headers());
        }

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("select", "");
        queryParams.put("select-type", "2");

        Response response =
                executePost(
                        args,
                        headers,
                        queryParams,
                        new SelectObjectContentRequest(
                                args.sqlExpression(),
                                args.requestProgress(),
                                args.inputSerialization(),
                                args.outputSerialization(),
                                args.scanStartRange(),
                                args.scanEndRange()));
        return new SelectResponseStream(response.body().byteStream());
    }

    /**
     * Sets encryption configuration of a bucket.
     *
     * <pre>Example:{@code
     * minioClient.setBucketEncryption(
     *     SetBucketEncryptionArgs.builder().bucket("my-bucketname").config(config).build());
     * }</pre>
     *
     * @param args {@link SetBucketEncryptionArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setBucketEncryption(SetBucketEncryptionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("encryption", "");

        Response response = executePut(args, null, queryParams, args.config(), 0);
        response.close();
    }

    /**
     * Gets encryption configuration of a bucket.
     *
     * <pre>Example:{@code
     * SseConfiguration config =
     *     minioClient.getBucketEncryption(
     *         GetBucketEncryptionArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link GetBucketEncryptionArgs} object.
     * @return {@link SseConfiguration} - Server-side encryption configuration.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public SseConfiguration getBucketEncryption(GetBucketEncryptionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("encryption", "");

        try (Response response = executeGet(args, null, queryParams)) {
            return Xml.unmarshal(SseConfiguration.class, response.body().charStream());
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode()
                    != ErrorCode.SERVER_SIDE_ENCRYPTION_CONFIGURATION_NOT_FOUND_ERROR) {
                throw e;
            }
        }

        return new SseConfiguration();
    }

    /**
     * Deletes encryption configuration of a bucket.
     *
     * <pre>Example:{@code
     * minioClient.deleteBucketEncryption(
     *     DeleteBucketEncryptionArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link DeleteBucketEncryptionArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void deleteBucketEncryption(DeleteBucketEncryptionArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("encryption", "");

        try {
            executeDelete(args, null, queryParams);
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode()
                    != ErrorCode.SERVER_SIDE_ENCRYPTION_CONFIGURATION_NOT_FOUND_ERROR) {
                throw e;
            }
        }
    }

    /**
     * Gets tags of a bucket.
     *
     * <pre>Example:{@code
     * Tags tags =
     *     minioClient.getBucketTags(GetBucketTagsArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link GetBucketTagsArgs} object.
     * @return {@link Tags} - Tags.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public Tags getBucketTags(GetBucketTagsArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("tagging", "");

        try (Response response = executeGet(args, null, queryParams)) {
            return Xml.unmarshal(Tags.class, response.body().charStream());
        } catch (ErrorResponseException e) {
            if (e.errorResponse().errorCode() != ErrorCode.NO_SUCH_TAG_SET) {
                throw e;
            }
        }

        return new Tags();
    }

    /**
     * Sets tags to a bucket.
     *
     * <pre>Example:{@code
     * Map<String, String> map = new HashMap<>();
     * map.put("Project", "Project One");
     * map.put("User", "jsmith");
     * minioClient.setBucketTags(
     *     SetBucketTagsArgs.builder().bucket("my-bucketname").tags(map).build());
     * }</pre>
     *
     * @param args {@link SetBucketTagsArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setBucketTags(SetBucketTagsArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("tagging", "");

        Response response = executePut(args, null, queryParams, args.tags(), 0);
        response.close();
    }

    /**
     * Deletes tags of a bucket.
     *
     * <pre>Example:{@code
     * minioClient.deleteBucketTags(DeleteBucketTagsArgs.builder().bucket("my-bucketname").build());
     * }</pre>
     *
     * @param args {@link DeleteBucketTagsArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void deleteBucketTags(DeleteBucketTagsArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("tagging", "");

        executeDelete(args, null, queryParams);
    }

    /**
     * Gets tags of an object.
     *
     * <pre>Example:{@code
     * Tags tags =
     *     minioClient.getObjectTags(
     *         GetObjectTagsArgs.builder().bucket("my-bucketname").object("my-objectname").build());
     * }</pre>
     *
     * @param args {@link GetObjectTagsArgs} object.
     * @return {@link Tags} - Tags.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public Tags getObjectTags(GetObjectTagsArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("tagging", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        try (Response response = executeGet(args, null, queryParams)) {
            return Xml.unmarshal(Tags.class, response.body().charStream());
        }
    }

    /**
     * Sets tags to an object.
     *
     * <pre>Example:{@code
     * Map<String, String> map = new HashMap<>();
     * map.put("Project", "Project One");
     * map.put("User", "jsmith");
     * minioClient.setObjectTags(
     *     SetObjectTagsArgs.builder()
     *         .bucket("my-bucketname")
     *         .object("my-objectname")
     *         .tags((map)
     *         .build());
     * }</pre>
     *
     * @param args {@link SetObjectTagsArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void setObjectTags(SetObjectTagsArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("tagging", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        Response response = executePut(args, null, queryParams, args.tags(), 0);
        response.close();
    }

    /**
     * Deletes tags of an object.
     *
     * <pre>Example:{@code
     * minioClient.deleteObjectTags(
     *     DeleteObjectTags.builder().bucket("my-bucketname").object("my-objectname").build());
     * }</pre>
     *
     * @param args {@link DeleteObjectTagsArgs} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public void deleteObjectTags(DeleteObjectTagsArgs args)
            throws ErrorResponseException, IllegalArgumentException, InsufficientDataException,
            InternalException, InvalidBucketNameException, InvalidKeyException,
            InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
            XmlParserException {
        checkArgs(args);

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("tagging", "");
        if (args.versionId() != null) queryParams.put("versionId", args.versionId());

        executeDelete(args, null, queryParams);
    }

    private long getAvailableSize(Object data, long expectedReadSize)
            throws IOException, InternalException {
        if (!(data instanceof BufferedInputStream)) {
            throw new InternalException(
                    "data must be BufferedInputStream. This should not happen.  "
                            + "Please report to https://github.com/minio/minio-java/issues/");
        }

        BufferedInputStream stream = (BufferedInputStream) data;
        stream.mark((int) expectedReadSize);

        byte[] buf = new byte[16384]; // 16KiB buffer for optimization
        long totalBytesRead = 0;
        while (totalBytesRead < expectedReadSize) {
            long bytesToRead = expectedReadSize - totalBytesRead;
            if (bytesToRead > buf.length) {
                bytesToRead = buf.length;
            }

            int bytesRead = stream.read(buf, 0, (int) bytesToRead);
            if (bytesRead < 0) {
                break; // reached EOF
            }

            totalBytesRead += bytesRead;
        }

        stream.reset();
        return totalBytesRead;
    }

    /**
     * Sets HTTP connect, write and read timeouts. A value of 0 means no timeout, otherwise values
     * must be between 1 and Integer.MAX_VALUE when converted to milliseconds.
     *
     * <pre>Example:{@code
     * minioClient.setTimeout(TimeUnit.SECONDS.toMillis(10), TimeUnit.SECONDS.toMillis(10),
     *     TimeUnit.SECONDS.toMillis(30));
     * }</pre>
     *
     * @param connectTimeout HTTP connect timeout in milliseconds.
     * @param writeTimeout   HTTP write timeout in milliseconds.
     * @param readTimeout    HTTP read timeout in milliseconds.
     */
    public void setTimeout(long connectTimeout, long writeTimeout, long readTimeout) {
        this.httpClient =
                this.httpClient
                        .newBuilder()
                        .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                        .writeTimeout(writeTimeout, TimeUnit.MILLISECONDS)
                        .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                        .build();
    }

    /**
     * Ignores check on server certificate for HTTPS connection.
     *
     * <pre>Example:{@code
     * minioClient.ignoreCertCheck();
     * }</pre>
     *
     * @throws KeyManagementException   thrown to indicate key management error.
     * @throws NoSuchAlgorithmException thrown to indicate missing of SSL library.
     */
    @SuppressFBWarnings(value = "SIC", justification = "Should not be used in production anyways.")
    public void ignoreCertCheck() throws KeyManagementException, NoSuchAlgorithmException {
        final TrustManager[] trustAllCerts =
                new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType)
                                    throws CertificateException {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType)
                                    throws CertificateException {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[]{};
                            }
                        }
                };

        final SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

        this.httpClient =
                this.httpClient
                        .newBuilder()
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                        .hostnameVerifier(
                                new HostnameVerifier() {
                                    @Override
                                    public boolean verify(String hostname, SSLSession session) {
                                        return true;
                                    }
                                })
                        .build();
    }

    /**
     * Sets application's name/version to user agent. For more information about user agent refer <a
     * href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html">#rfc2616</a>.
     *
     * @param name    Your application name.
     * @param version Your application version.
     */
    @SuppressWarnings("unused")
    public void setAppInfo(String name, String version) {
        if (name == null || version == null) {
            // nothing to do
            return;
        }

        this.userAgent = DEFAULT_USER_AGENT + " " + name.trim() + "/" + version.trim();
    }

    /**
     * Enables HTTP call tracing and written to traceStream.
     *
     * @param traceStream {@link OutputStream} for writing HTTP call tracing.
     * @see #traceOff
     */
    public void traceOn(OutputStream traceStream) {
        if (traceStream == null) {
            throw new NullPointerException();
        } else {
            this.traceStream =
                    new PrintWriter(new OutputStreamWriter(traceStream, StandardCharsets.UTF_8), true);
        }
    }

    /**
     * Disables HTTP call tracing previously enabled.
     *
     * @throws IOException upon connection error
     * @see #traceOn
     */
    public void traceOff() throws IOException {
        this.traceStream = null;
    }

    /**
     * Enables accelerate endpoint for Amazon S3 endpoint.
     */
    public void enableAccelerateEndpoint() {
        this.isAcceleratedHost = true;
    }

    /**
     * Disables accelerate endpoint for Amazon S3 endpoint.
     */
    public void disableAccelerateEndpoint() {
        this.isAcceleratedHost = false;
    }

    /**
     * Enables dual-stack endpoint for Amazon S3 endpoint.
     */
    public void enableDualStackEndpoint() {
        this.isDualStackHost = true;
    }

    /**
     * Disables dual-stack endpoint for Amazon S3 endpoint.
     */
    public void disableDualStackEndpoint() {
        this.isDualStackHost = false;
    }

    /**
     * Enables virtual-style endpoint.
     */
    public void enableVirtualStyleEndpoint() {
        this.useVirtualStyle = true;
    }

    /**
     * Disables virtual-style endpoint.
     */
    public void disableVirtualStyleEndpoint() {
        this.useVirtualStyle = false;
    }

    private static class NotificationResultRecords {
        Response response = null;
        Scanner scanner = null;
        ObjectMapper mapper = null;

        public NotificationResultRecords(Response response) {
            this.response = response;
            this.scanner = new Scanner(response.body().charStream()).useDelimiter("\n");
            this.mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        }

        /**
         * returns closeable iterator of result of notification records.
         */
        public CloseableIterator<Result<NotificationRecords>> closeableIterator() {
            return new CloseableIterator<Result<NotificationRecords>>() {
                String recordsString = null;
                NotificationRecords records = null;
                boolean isClosed = false;

                @Override
                public void close() throws IOException {
                    if (!isClosed) {
                        try {
                            response.body().close();
                            scanner.close();
                        } finally {
                            isClosed = true;
                        }
                    }
                }

                public boolean populate() {
                    if (isClosed) {
                        return false;
                    }

                    if (recordsString != null) {
                        return true;
                    }

                    while (scanner.hasNext()) {
                        recordsString = scanner.next().trim();
                        if (!recordsString.equals("")) {
                            break;
                        }
                    }

                    if (recordsString == null || recordsString.equals("")) {
                        try {
                            close();
                        } catch (IOException e) {
                            isClosed = true;
                        }
                        return false;
                    }
                    return true;
                }

                @Override
                public boolean hasNext() {
                    return populate();
                }

                @Override
                public Result<NotificationRecords> next() {
                    if (isClosed) {
                        throw new NoSuchElementException();
                    }
                    if ((recordsString == null || recordsString.equals("")) && !populate()) {
                        throw new NoSuchElementException();
                    }

                    try {
                        records = mapper.readValue(recordsString, NotificationRecords.class);
                        return new Result<>(records);
                    } catch (JsonMappingException e) {
                        return new Result<>(e);
                    } catch (JsonParseException e) {
                        return new Result<>(e);
                    } catch (IOException e) {
                        return new Result<>(e);
                    } finally {
                        recordsString = null;
                        records = null;
                    }
                }
            };
        }
    }

    /**
     * Do <a
     * href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_AbortMultipartUpload.html">AbortMultipartUpload
     * S3 API</a>.
     *
     * @param bucketName Name of the bucket.
     * @param objectName Object name in the bucket.
     * @param uploadId   Upload ID.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    protected void abortMultipartUpload(String bucketName, String objectName, String uploadId)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put(UPLOAD_ID, uploadId);

        Response response =
                execute(
                        Method.DELETE,
                        bucketName,
                        objectName,
                        getRegion(bucketName, this.region),
                        null,
                        queryParams,
                        null,
                        0);
        response.close();
    }


    /**
     * Do <a
     * href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html">CompleteMultipartUpload
     * S3 API</a>.
     *
     * @param bucketName       Name of the bucket.
     * @param region           Region of the bucket.
     * @param objectName       Object name in the bucket.
     * @param uploadId         Upload ID.
     * @param parts            List of parts.
     * @param extraHeaders     Extra headers.
     * @param extraQueryParams Extra query parameters.
     * @return {@link ObjectWriteResponse} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public ObjectWriteResponse completeMultipartUpload(
            String bucketName,
            String region,
            String objectName,
            String uploadId,
            Part[] parts,
            Multimap<String, String> extraHeaders,
            Multimap<String, String> extraQueryParams)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        if (extraQueryParams != null) {
            queryParams.putAll(extraQueryParams);
        }
        queryParams.put(UPLOAD_ID, uploadId);

        try (Response response =
                     execute(
                             Method.POST,
                             bucketName,
                             objectName,
                             getRegion(bucketName, region),
                             extraHeaders,
                             queryParams,
                             new CompleteMultipartUpload(parts),
                             0)) {
            String bodyContent = new String(response.body().bytes(), StandardCharsets.UTF_8);
            bodyContent = bodyContent.trim();
            if (!bodyContent.isEmpty()) {
                try {
                    if (Xml.validate(ErrorResponse.class, bodyContent)) {
                        ErrorResponse errorResponse = Xml.unmarshal(ErrorResponse.class, bodyContent);
                        throw new ErrorResponseException(errorResponse, response);
                    }
                } catch (XmlParserException e) {
                    // As it is not <Error> message, fall-back to parse CompleteMultipartUploadOutput XML.
                }

                try {
                    CompleteMultipartUploadOutput result =
                            Xml.unmarshal(CompleteMultipartUploadOutput.class, bodyContent);
                    return new ObjectWriteResponse(
                            response.headers(),
                            result.bucket(),
                            result.location(),
                            result.object(),
                            result.etag(),
                            response.header("x-amz-version-id"));
                } catch (XmlParserException e) {
                    // As this CompleteMultipartUpload REST call succeeded, just log it.
                    Logger.getLogger(MinioClient.class.getName())
                            .warning(
                                    "S3 service returned unknown XML for CompleteMultipartUpload REST API. "
                                            + bodyContent);
                }
            }

            return new ObjectWriteResponse(
                    response.headers(),
                    bucketName,
                    region,
                    objectName,
                    null,
                    response.header("x-amz-version-id"));
        }
    }


    /**
     * Do <a
     * href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html">CreateMultipartUpload
     * S3 API</a>.
     *
     * @param bucketName Name of the bucket.
     * @param region     Region name of buckets in S3 service.
     * @param objectName Object name in the bucket.
     * @param headers    Request headers.
     * @return String - Contains upload ID.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public String createMultipartUpload(
            String bucketName,
            String region,
            String objectName,
            Multimap<String, String> headers,
            Multimap<String, String> extraQueryParams)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        if (extraQueryParams != null) {
            queryParams.putAll(extraQueryParams);
        }
        queryParams.put("uploads", "");

        Multimap<String, String> headersCopy = HashMultimap.create();
        if (headers != null) {
            headersCopy.putAll(headers);
        }
        // set content type if not set already
        if (!headersCopy.containsKey("Content-Type")) {
            headersCopy.put("Content-Type", "application/octet-stream");
        }

        try (Response response =
                     execute(
                             Method.POST,
                             bucketName,
                             objectName,
                             getRegion(bucketName, region),
                             headersCopy,
                             queryParams,
                             null,
                             0)) {
            InitiateMultipartUploadResult result =
                    Xml.unmarshal(InitiateMultipartUploadResult.class, response.body().charStream());
            return result.uploadId();
        }
    }

    /**
     * Do <a
     * href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObjects.html">DeleteObjects S3
     * API</a>.
     *
     * @param bucketName Name of the bucket.
     * @param objectList List of object names.
     * @param quiet      Quiet flag.
     * @return {@link DeleteResult} - Contains delete result.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    protected DeleteResult deleteObjects(
            String bucketName, List<DeleteObject> objectList, boolean quiet, boolean bypassGovernanceMode)
            throws InvalidBucketNameException, NoSuchAlgorithmException, InsufficientDataException,
            IOException, InvalidKeyException, ServerException, XmlParserException,
            ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("delete", "");

        Multimap<String, String> headers = null;
        if (bypassGovernanceMode) {
            headers = HashMultimap.create();
            headers.put("x-amz-bypass-governance-retention", "true");
        }

        try (Response response =
                     execute(
                             Method.POST,
                             bucketName,
                             null,
                             getRegion(bucketName, null),
                             headers,
                             queryParams,
                             new DeleteRequest(objectList, quiet),
                             0)) {
            String bodyContent = new String(response.body().bytes(), StandardCharsets.UTF_8);
            try {
                if (Xml.validate(DeleteError.class, bodyContent)) {
                    DeleteError error = Xml.unmarshal(DeleteError.class, bodyContent);
                    return new DeleteResult(error);
                }
            } catch (XmlParserException e) {
                // As it is not <Error> message, parse it as <DeleteResult> message.
                // Ignore this exception
            }

            return Xml.unmarshal(DeleteResult.class, bodyContent);
        }
    }

    private Multimap<String, String> getCommonListObjectsQueryParams(
            String delimiter, boolean useUrlEncodingType, int maxKeys, String prefix) {
        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("delimiter", (delimiter == null) ? "" : delimiter);
        if (useUrlEncodingType) {
            queryParams.put("encoding-type", "url");
        }
        queryParams.put("max-keys", Integer.toString(maxKeys > 0 ? maxKeys : 1000));
        queryParams.put("prefix", (prefix == null) ? "" : prefix);
        return queryParams;
    }

    protected ListBucketResultV2 listObjectsV2(
            String bucketName,
            String region,
            String delimiter,
            boolean useUrlEncodingType,
            String startAfter,
            int maxKeys,
            String prefix,
            String continuationToken,
            boolean fetchOwner,
            boolean includeUserMetadata,
            Multimap<String, String> extraHeaders,
            Multimap<String, String> extraQueryParams)
            throws InvalidKeyException, InvalidBucketNameException, IllegalArgumentException,
            NoSuchAlgorithmException, InsufficientDataException, ServerException, XmlParserException,
            ErrorResponseException, InternalException, InvalidResponseException, IOException {
        Multimap<String, String> queryParams = HashMultimap.create();
        if (extraQueryParams != null) {
            queryParams.putAll(extraQueryParams);
        }
        queryParams.putAll(
                getCommonListObjectsQueryParams(delimiter, useUrlEncodingType, maxKeys, prefix));
        queryParams.put("list-type", "2");
        if (continuationToken != null) {
            queryParams.put("continuation-token", continuationToken);
        }
        if (fetchOwner) {
            queryParams.put("fetch-owner", "true");
        }
        if (startAfter != null) {
            queryParams.put("start-after", startAfter);
        }
        if (includeUserMetadata) {
            queryParams.put("metadata", "true");
        }

        try (Response response =
                     execute(
                             Method.GET,
                             bucketName,
                             null,
                             getRegion(bucketName, region),
                             extraHeaders,
                             queryParams,
                             null,
                             0)) {
            return Xml.unmarshal(ListBucketResultV2.class, response.body().charStream());
        }
    }

    protected ListBucketResultV1 listObjectsV1(
            String bucketName,
            String region,
            String delimiter,
            boolean useUrlEncodingType,
            String marker,
            int maxKeys,
            String prefix,
            Multimap<String, String> extraHeaders,
            Multimap<String, String> extraQueryParams)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        if (extraQueryParams != null) {
            queryParams.putAll(extraQueryParams);
        }
        queryParams.putAll(
                getCommonListObjectsQueryParams(delimiter, useUrlEncodingType, maxKeys, prefix));
        if (marker != null) {
            queryParams.put("marker", marker);
        }

        try (Response response =
                     execute(
                             Method.GET,
                             bucketName,
                             null,
                             getRegion(bucketName, region),
                             extraHeaders,
                             queryParams,
                             null,
                             0)) {
            return Xml.unmarshal(ListBucketResultV1.class, response.body().charStream());
        }
    }

    protected ListVersionsResult listObjectVersions(
            String bucketName,
            String region,
            String delimiter,
            boolean useUrlEncodingType,
            String keyMarker,
            int maxKeys,
            String prefix,
            String versionIdMarker,
            Multimap<String, String> extraHeaders,
            Multimap<String, String> extraQueryParams)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        if (extraQueryParams != null) {
            queryParams.putAll(extraQueryParams);
        }
        queryParams.putAll(
                getCommonListObjectsQueryParams(delimiter, useUrlEncodingType, maxKeys, prefix));
        if (keyMarker != null) {
            queryParams.put("key-marker", keyMarker);
        }
        if (versionIdMarker != null) {
            queryParams.put("version-id-marker", versionIdMarker);
        }
        queryParams.put("versions", "");

        try (Response response =
                     execute(
                             Method.GET,
                             bucketName,
                             null,
                             getRegion(bucketName, region),
                             extraHeaders,
                             queryParams,
                             null,
                             0)) {
            return Xml.unmarshal(ListVersionsResult.class, response.body().charStream());
        }
    }

    /**
     * Do <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html">PutObject S3
     * API</a>.
     *
     * @param bucketName       Name of the bucket.
     * @param objectName       Object name in the bucket.
     * @param data             Object data must be BufferedInputStream, RandomAccessFile, byte[] or String.
     * @param length           Length of object data.
     * @param headers          Additional headers.
     * @param extraQueryParams Additional query parameters if any.
     * @return {@link ObjectWriteResponse} object.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    protected ObjectWriteResponse putObject(
            String bucketName,
            String region,
            String objectName,
            Object data,
            int length,
            Multimap<String, String> headers,
            Multimap<String, String> extraQueryParams)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        if (!(data instanceof BufferedInputStream
                || data instanceof RandomAccessFile
                || data instanceof byte[]
                || data instanceof CharSequence)) {
            throw new IllegalArgumentException(
                    "data must be BufferedInputStream, RandomAccessFile, byte[] or String");
        }

        try (Response response =
                     execute(
                             Method.PUT,
                             bucketName,
                             objectName,
                             getRegion(bucketName, region),
                             headers,
                             extraQueryParams,
                             data,
                             length)) {
            return new ObjectWriteResponse(
                    response.headers(),
                    bucketName,
                    region,
                    objectName,
                    response.header("ETag").replaceAll("\"", ""),
                    response.header("x-amz-version-id"));
        }
    }

    /**
     * Do <a
     * href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListMultipartUploads.html">ListMultipartUploads
     * S3 API</a>.
     *
     * @param bucketName     Name of the bucket.
     * @param delimiter      Delimiter.
     * @param keyMarker      Key marker.
     * @param maxUploads     Maximum upload information to fetch.
     * @param prefix         Prefix.
     * @param uploadIdMarker Upload ID marker.
     * @return {@link ListMultipartUploadsResult} - Contains uploads information.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    protected ListMultipartUploadsResult listMultipartUploads(
            String bucketName,
            String delimiter,
            String keyMarker,
            Integer maxUploads,
            String prefix,
            String uploadIdMarker)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("uploads", "");

        if (delimiter != null) {
            queryParams.put("delimiter", delimiter);
        } else {
            queryParams.put("delimiter", "");
        }

        if (keyMarker != null) {
            queryParams.put("key-marker", keyMarker);
        }

        if (maxUploads != null) {
            queryParams.put("max-uploads", Integer.toString(maxUploads));
        }

        if (prefix != null) {
            queryParams.put("prefix", prefix);
        } else {
            queryParams.put("prefix", "");
        }

        if (uploadIdMarker != null) {
            queryParams.put("upload-id-marker", uploadIdMarker);
        }

        // Setting it as default to encode the object keys in the response
        queryParams.put("encoding-type", "url");

        Response response =
                execute(
                        Method.GET, bucketName, null, getRegion(bucketName, null), null, queryParams, null, 0);

        try (ResponseBody body = response.body()) {
            return Xml.unmarshal(ListMultipartUploadsResult.class, body.charStream());
        }
    }

    /**
     * Do <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListParts.html">ListParts S3
     * API</a>.
     *
     * @param bucketName       Name of the bucket.
     * @param objectName       Object name in the bucket.
     * @param maxParts         Maximum parts information to fetch.
     * @param partNumberMarker Part number marker.
     * @param uploadId         Upload ID.
     * @return {@link ListPartsResult} - Contains parts information.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    protected ListPartsResult listParts(
            String bucketName,
            String objectName,
            Integer maxParts,
            Integer partNumberMarker,
            String uploadId)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();

        if (maxParts != null) {
            queryParams.put("max-parts", Integer.toString(maxParts));
        }

        if (partNumberMarker != null) {
            queryParams.put("part-number-marker", Integer.toString(partNumberMarker));
        }

        queryParams.put(UPLOAD_ID, uploadId);

        Response response =
                execute(
                        Method.GET,
                        bucketName,
                        objectName,
                        getRegion(bucketName, null),
                        null,
                        queryParams,
                        null,
                        0);

        try (ResponseBody body = response.body()) {
            return Xml.unmarshal(ListPartsResult.class, body.charStream());
        }
    }

    /**
     * Do <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPart.html">UploadPart S3
     * API</a>.
     *
     * @param bucketName Name of the bucket.
     * @param objectName Object name in the bucket.
     * @param data       Object data must be BufferedInputStream, RandomAccessFile, byte[] or String.
     * @param length     Length of object data.
     * @param uploadId   Upload ID.
     * @param partNumber Part number.
     * @param headerMap  Additional headers.
     * @return String - Contains ETag.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    public String uploadPart(
            String bucketName,
            String objectName,
            Object data,
            int length,
            String uploadId,
            int partNumber,
            Map<String, String> headerMap)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        if (!(data instanceof BufferedInputStream
                || data instanceof RandomAccessFile
                || data instanceof byte[]
                || data instanceof CharSequence)) {
            throw new IllegalArgumentException(
                    "data must be BufferedInputStream, RandomAccessFile, byte[] or String");
        }

        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("partNumber", Integer.toString(partNumber));
        queryParams.put(UPLOAD_ID, uploadId);

        try (Response response =
                     execute(
                             Method.PUT,
                             bucketName,
                             objectName,
                             getRegion(bucketName, null),
                             (headerMap != null) ? Multimaps.forMap(headerMap) : null,
                             queryParams,
                             data,
                             length)) {
            return response.header("ETag").replaceAll("\"", "");
        }
    }

    /**
     * Do <a
     * href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy
     * S3 API</a>.
     *
     * @param bucketName Name of the bucket.
     * @param objectName Object name in the bucket.
     * @param uploadId   Upload ID.
     * @param partNumber Part number.
     * @param headers    Source object definitions.
     * @return String - Contains ETag.
     * @throws ErrorResponseException     thrown to indicate S3 service returned an error response.
     * @throws IllegalArgumentException   throws to indicate invalid argument passed.
     * @throws InsufficientDataException  thrown to indicate not enough data available in InputStream.
     * @throws InternalException          thrown to indicate internal library error.
     * @throws InvalidBucketNameException thrown to indicate invalid bucket name passed.
     * @throws InvalidKeyException        thrown to indicate missing of HMAC SHA-256 library.
     * @throws InvalidResponseException   thrown to indicate S3 service returned invalid or no error
     *                                    response.
     * @throws IOException                thrown to indicate I/O error on S3 operation.
     * @throws NoSuchAlgorithmException   thrown to indicate missing of MD5 or SHA-256 digest library.
     * @throws XmlParserException         thrown to indicate XML parsing error.
     */
    protected String uploadPartCopy(
            String bucketName,
            String objectName,
            String uploadId,
            int partNumber,
            Multimap<String, String> headers)
            throws InvalidBucketNameException, IllegalArgumentException, NoSuchAlgorithmException,
            InsufficientDataException, IOException, InvalidKeyException, ServerException,
            XmlParserException, ErrorResponseException, InternalException, InvalidResponseException {
        Multimap<String, String> queryParams = HashMultimap.create();
        queryParams.put("partNumber", Integer.toString(partNumber));
        queryParams.put("uploadId", uploadId);
        Response response =
                execute(
                        Method.PUT,
                        bucketName,
                        objectName,
                        getRegion(bucketName, null),
                        headers,
                        queryParams,
                        null,
                        0);
        try (ResponseBody body = response.body()) {
            CopyPartResult result = Xml.unmarshal(CopyPartResult.class, body.charStream());
            return result.etag();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        HttpUrl baseUrl;
        String region;
        String accessKey;
        String secretKey;
        OkHttpClient httpClient;
        boolean isAwsHost;
        boolean isAwsChinaHost;
        boolean isAcceleratedHost;
        boolean isDualStackHost;
        boolean useVirtualStyle;
        String regionInUrl;

        public Builder() {
        }

        private boolean isAwsEndpoint(String endpoint) {
            return (endpoint.startsWith("s3.") || isAwsAccelerateEndpoint(endpoint))
                    && (endpoint.endsWith(".amazonaws.com") || endpoint.endsWith(".amazonaws.com.cn"));
        }

        private boolean isAwsAccelerateEndpoint(String endpoint) {
            return endpoint.startsWith("s3-accelerate.");
        }

        private boolean isAwsDualStackEndpoint(String endpoint) {
            return endpoint.contains(".dualstack.");
        }

        /**
         * Extracts region from AWS endpoint if available. Region is placed at second token normal
         * endpoints and third token for dualstack endpoints.
         *
         * <p>Region is marked in square brackets in below examples.
         * <pre>
         * https://s3.[us-east-2].amazonaws.com
         * https://s3.dualstack.[ca-central-1].amazonaws.com
         * https://s3.[cn-north-1].amazonaws.com.cn
         * https://s3.dualstack.[cn-northwest-1].amazonaws.com.cn
         */
        private String extractRegion(String endpoint) {
            String[] tokens = endpoint.split("\\.");
            String token = tokens[1];

            // If token is "dualstack", then region might be in next token.
            if (token.equals("dualstack")) {
                token = tokens[2];
            }

            // If token is equal to "amazonaws", region is not passed in the endpoint.
            if (token.equals("amazonaws")) {
                return null;
            }

            // Return token as region.
            return token;
        }

        private void setBaseUrl(HttpUrl url) {
            String host = url.host();
            this.isAwsHost = isAwsEndpoint(host);
            this.isAwsChinaHost = false;
            if (this.isAwsHost) {
                this.isAwsChinaHost = host.endsWith(".cn");
                url =
                        url.newBuilder()
                                .host(this.isAwsChinaHost ? "amazonaws.com.cn" : "amazonaws.com")
                                .build();
                this.isAcceleratedHost = isAwsAccelerateEndpoint(host);
                this.isDualStackHost = isAwsDualStackEndpoint(host);
                this.regionInUrl = extractRegion(host);
                this.useVirtualStyle = true;
            } else {
                this.useVirtualStyle = host.endsWith("aliyuncs.com");
            }

            this.baseUrl = url;
        }

        /**
         * copied logic from
         * https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/CustomTrust.java
         */
        private OkHttpClient enableExternalCertificates(OkHttpClient httpClient, String filename)
                throws GeneralSecurityException, IOException {
            Collection<? extends Certificate> certificates = null;
            try (FileInputStream fis = new FileInputStream(filename)) {
                certificates = CertificateFactory.getInstance("X.509").generateCertificates(fis);
            }

            if (certificates == null || certificates.isEmpty()) {
                throw new IllegalArgumentException("expected non-empty set of trusted certificates");
            }

            char[] password = "password" .toCharArray(); // Any password will work.

            // Put the certificates a key store.
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            // By convention, 'null' creates an empty key store.
            keyStore.load(null, password);

            int index = 0;
            for (Certificate certificate : certificates) {
                String certificateAlias = Integer.toString(index++);
                keyStore.setCertificateEntry(certificateAlias, certificate);
            }

            // Use it to build an X509 trust manager.
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);

            final KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
            final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, null);
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return httpClient
                    .newBuilder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustManagers[0])
                    .build();
        }

        protected void validateNotNull(Object arg, String argName) {
            if (arg == null) {
                throw new IllegalArgumentException(argName + " must not be null.");
            }
        }

        protected void validateNotEmptyString(String arg, String argName) {
            validateNotNull(arg, argName);
            if (arg.isEmpty()) {
                throw new IllegalArgumentException(argName + " must be a non-empty string.");
            }
        }

        protected void validateNullOrNotEmptyString(String arg, String argName) {
            if (arg != null && arg.isEmpty()) {
                throw new IllegalArgumentException(argName + " must be a non-empty string.");
            }
        }

        private void validateUrl(HttpUrl url) {
            if (!url.encodedPath().equals("/")) {
                throw new IllegalArgumentException("no path allowed in endpoint " + url);
            }
        }

        private void validateHostnameOrIPAddress(String endpoint) {
            // Check endpoint is IPv4 or IPv6.
            if (InetAddressValidator.getInstance().isValid(endpoint)) {
                return;
            }

            // Check endpoint is a hostname.

            // Refer https://en.wikipedia.org/wiki/Hostname#Restrictions_on_valid_host_names
            // why checks are done like below
            if (endpoint.length() < 1 || endpoint.length() > 253) {
                throw new IllegalArgumentException("invalid hostname");
            }

            for (String label : endpoint.split("\\.")) {
                if (label.length() < 1 || label.length() > 63) {
                    throw new IllegalArgumentException("invalid hostname");
                }

                if (!(label.matches("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$"))) {
                    throw new IllegalArgumentException("invalid hostname");
                }
            }
        }

        private HttpUrl getBaseUrl(String endpoint) {
            validateNotEmptyString(endpoint, "endpoint");
            HttpUrl url = HttpUrl.parse(endpoint);
            if (url == null) {
                validateHostnameOrIPAddress(endpoint);
                url = new HttpUrl.Builder().scheme("https").host(endpoint).build();
            } else {
                validateUrl(url);
            }

            return url;
        }

        public Builder endpoint(String endpoint) {
            setBaseUrl(getBaseUrl(endpoint));
            return this;
        }

        public Builder endpoint(String endpoint, int port, boolean secure) {
            HttpUrl url = getBaseUrl(endpoint);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be in range of 1 to 65535");
            }
            url = url.newBuilder().port(port).scheme(secure ? "https" : "http").build();

            setBaseUrl(url);
            return this;
        }

        /**
         * Remove this method when all deprecated MinioClient constructors are removed.
         */
        private Builder endpoint(String endpoint, Integer port, Boolean secure) {
            HttpUrl url = getBaseUrl(endpoint);
            if (port != null) {
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("port must be in range of 1 to 65535");
                }

                url = url.newBuilder().port(port).build();
            }

            if (secure != null) {
                url = url.newBuilder().scheme(secure ? "https" : "http").build();
            }

            setBaseUrl(url);
            return this;
        }

        public Builder endpoint(URL url) {
            validateNotNull(url, "url");
            return endpoint(HttpUrl.get(url));
        }

        public Builder endpoint(HttpUrl url) {
            validateNotNull(url, "url");
            validateUrl(url);
            setBaseUrl(url);
            return this;
        }

        public Builder region(String region) {
            validateNullOrNotEmptyString(region, "region");
            this.region = region;
            this.regionInUrl = region;
            return this;
        }

        public Builder credentials(String accessKey, String secretKey) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            return this;
        }

        public Builder httpClient(OkHttpClient httpClient) {
            validateNotNull(httpClient, "http client");
            this.httpClient = httpClient;
            return this;
        }

        public MyClient build() {
            validateNotNull(baseUrl, "endpoint");
            if (isAwsChinaHost && regionInUrl == null && region == null) {
                throw new IllegalArgumentException("Region missing in Amazon S3 China endpoint " + baseUrl);
            }

            if (httpClient == null) {
                this.httpClient =
                        new OkHttpClient()
                                .newBuilder()
                                .connectTimeout(DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MINUTES)
                                .writeTimeout(DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MINUTES)
                                .readTimeout(DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MINUTES)
                                .protocols(Arrays.asList(Protocol.HTTP_1_1))
                                .build();
                String filename = System.getenv("SSL_CERT_FILE");
                if (filename != null && !filename.isEmpty()) {
                    try {
                        this.httpClient = enableExternalCertificates(this.httpClient, filename);
                    } catch (GeneralSecurityException | IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            return new MyClient(
                    baseUrl,
                    (region != null) ? region : regionInUrl,
                    isAwsHost,
                    isAcceleratedHost,
                    isDualStackHost,
                    useVirtualStyle,
                    accessKey,
                    secretKey,
                    httpClient);
        }
    }
}

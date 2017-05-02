package eu.sipria.sbt.s3

import java.io.{File, InputStream}
import java.net.{InetAddress, URI, URL}
import java.util.concurrent.ConcurrentHashMap

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth._
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3._
import com.amazonaws.services.s3.model.{KMSEncryptionMaterialsProvider, _}
import org.apache.ivy.util.url.URLHandler
import org.apache.ivy.util.{CopyProgressEvent, CopyProgressListener}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.matching.Regex

object S3URLHandler {
  // This is for matching region names in URLs or host names
  private val RegionMatcher: Regex = Regions.values().map(_.getName).sortBy(-1 * _.length).mkString("|").r

  private class S3URLInfo(
    available: Boolean,
    contentLength: Long,
    lastModified: Long
  ) extends URLHandler.URLInfo(available, contentLength, lastModified)
}

/**
 * This implements the Ivy URLHandler
 */
final class S3URLHandler(options: S3ResolverOptions) extends URLHandler {
  import S3URLHandler._
  import org.apache.ivy.util.url.URLHandler.{UNAVAILABLE, URLInfo}

  // Cache of Bucket Name => AmazonS3 Client Instance
  private val amazonS3ClientCache: ConcurrentHashMap[String,AmazonS3] = new ConcurrentHashMap()

  private val amazonS3EncryptionClientCache: ConcurrentHashMap[String, AmazonS3Encryption] = new ConcurrentHashMap()

  // Cache of Bucket Name => true/false (requires Server Side Encryption or not)
  private val bucketRequiresSSE: ConcurrentHashMap[String,Boolean] = new ConcurrentHashMap()

  def isReachable(url: URL): Boolean = getURLInfo(url).isReachable
  def isReachable(url: URL, timeout: Int): Boolean = getURLInfo(url, timeout).isReachable
  def getContentLength(url: URL): Long = getURLInfo(url).getContentLength
  def getContentLength(url: URL, timeout: Int): Long = getURLInfo(url, timeout).getContentLength
  def getLastModified(url: URL): Long = getURLInfo(url).getLastModified
  def getLastModified(url: URL, timeout: Int): Long = getURLInfo(url, timeout).getLastModified
  def getURLInfo(url: URL): URLInfo = getURLInfo(url, 0)

  def getCredentialsProvider(bucket: String): AWSCredentialsProvider = {
    options.log.info("S3URLHandler - Looking up AWS Credentials for bucket: %s ..." format bucket)

    val credentialsProvider: AWSCredentialsProvider = try {
      options.credentialsProvider(bucket)
    } catch {
      case ex: com.amazonaws.AmazonClientException =>
        options.log.error("Unable to find AWS Credentials.")
        throw ex
    }

    options.log.info("S3URLHandler - Using AWS Access Key Id: %s for bucket: %s" format(
      credentialsProvider.getCredentials.getAWSAccessKeyId,
      bucket
    ))

    credentialsProvider
  }

  def getProxyConfiguration: ClientConfiguration = {
    val configuration = new ClientConfiguration()
    for {
      proxyHost <- Option( System.getProperty("https.proxyHost") )
      proxyPort <- Option( System.getProperty("https.proxyPort").toInt )
    } {
      configuration.setProxyHost(proxyHost)
      configuration.setProxyPort(proxyPort)
    }
    configuration
  }

  def getClientForBucket(bucket: String, url: URL): AmazonS3 = {

    Option(amazonS3ClientCache.get(bucket)) match {
      case Some(client) => client
      case None =>
        val clientBuilder = AmazonS3Client.builder()
          .withCredentials(getCredentialsProvider(bucket))
          .withClientConfiguration(getProxyConfiguration)

        def deprecatedClient = new AmazonS3Client(getCredentialsProvider(bucket), getProxyConfiguration)

        val client = clientBuilder
          //.withRegion(getRegion(url, bucket, clientBuilder.withRegion("us-east-1").build))
          .withRegion(getRegion(url, bucket, deprecatedClient))
          .build()

        amazonS3ClientCache.put(bucket, client)

        options.log.info("S3URLHandler - Created S3 Client for bucket: %s and region: %s" format(
          bucket,
          client.getRegionName
        ))

        client
    }
  }

  def getEncryptionClientForBucket(bucket: String, url: URL, keyId: String, client: AmazonS3): AmazonS3Encryption = {
    Option(amazonS3EncryptionClientCache.get(bucket)) match {
      case Some(encryptionClient) => encryptionClient
      case None =>
        val materialProvider = new KMSEncryptionMaterialsProvider(keyId)

        val encryptionClient = new AmazonS3EncryptionClientBuilder()
          .withEncryptionMaterials(materialProvider)
          .withCredentials(getCredentialsProvider(bucket))
          .withCryptoConfiguration(
            new CryptoConfiguration()
              .withAwsKmsRegion(client.getRegion.toAWSRegion)
          ).build()

        amazonS3EncryptionClientCache.put(bucket, encryptionClient)

        options.log.info("S3URLHandler - Created S3 Encryption Client for bucket: %s and region: %s" format(
          bucket,
          client.getRegionName
        ))

        encryptionClient
    }
  }

  def getClientBucketAndKey(url: URL): (AmazonS3, String, String) = {
    val (bucket, key) = getBucketAndKey(url)

    val client = getClientForBucket(bucket, url)

    (client, bucket, key)
  }

  def getURLInfo(url: URL, timeout: Int): URLInfo = try {
    options.log.debug(s"getURLInfo($url, $timeout)")
    
    val (client, bucket, key) = getClientBucketAndKey(url)
    
    val meta: ObjectMetadata = client.getObjectMetadata(bucket, key)
    
    val available: Boolean = true
    val contentLength: Long = meta.getContentLength
    val lastModified: Long = meta.getLastModified.getTime
    
    new S3URLInfo(available, contentLength, lastModified)
  } catch {
    case ex: AmazonS3Exception if ex.getStatusCode == 404 => UNAVAILABLE
  }
  
  def openStream(url: URL): InputStream = {
    options.log.debug(s"openStream($url)")
    
    val (client, bucket, key) = getClientBucketAndKey(url)
    val obj: S3Object = client.getObject(bucket, key)
    obj.getObjectContent
  }
  
  /**
   * A directory listing for keys/directories under this prefix
   */
  def list(url: URL): Seq[URL] = {
    options.log.debug(s"list($url)")
    
    val (client, bucket, key /* key is the prefix in this case */) = getClientBucketAndKey(url)
    
    // We want the prefix to have a trailing slash
    val prefix: String = key.stripSuffix("/") + "/"
    
    val request: ListObjectsRequest = {
      new ListObjectsRequest().withBucketName(bucket).withPrefix(prefix).withDelimiter("/")
    }
    
    val listing: ObjectListing = client.listObjects(request)
    
    require(!listing.isTruncated, "Truncated ObjectListing!  Making additional calls currently isn't implemented!")
    
    val keys: Seq[String] = listing.getCommonPrefixes.asScala ++ listing.getObjectSummaries.asScala.map{ _.getKey }
    
    val res: Seq[URL] = keys.map { k: String =>
      new URL(url.toString.stripSuffix("/") + "/" + k.stripPrefix(prefix))
    }
    
    options.log.debug(s"list($url) => \n  "+res.mkString("\n  "))
    
    res
  }
  
  def download(src: URL, dest: File, l: CopyProgressListener): Unit = {
    options.log.debug(s"download($src, $dest)")

    val (client, bucket, key) = getClientBucketAndKey(src)
    
    val event: CopyProgressEvent = new CopyProgressEvent()
    if (null != l) l.start(event)
    
    val meta: ObjectMetadata = client.getObject(new GetObjectRequest(bucket, key), dest)
    dest.setLastModified(meta.getLastModified.getTime)
    
    if (null != l) l.end(event) //l.progress(evt.update(EMPTY_BUFFER, 0, meta.getContentLength))
  }

  def upload(src: File, dest: URL, l: CopyProgressListener): Unit = {
    options.log.debug(s"upload($src, $dest)")

    val event: CopyProgressEvent = new CopyProgressEvent()
    if (null != l) l.start(event)

    val (client, bucket, key) = getClientBucketAndKey(dest)

    // Nested helper method for performing the actual PUT
    def putImpl(serverSideEncryption: Boolean, kmsCustomerMasterKeyID: Option[String] = None): PutObjectResult = {
      val meta: ObjectMetadata = new ObjectMetadata()

      kmsCustomerMasterKeyID match {
        case Some(keyId) =>
          meta.setHeader(Headers.SERVER_SIDE_ENCRYPTION, SSEAlgorithm.KMS.getAlgorithm)
          meta.setHeader(Headers.SERVER_SIDE_ENCRYPTION_AWS_KMS_KEYID, keyId)

          client.putObject(new PutObjectRequest(bucket, key, src).withMetadata(meta))

        case None =>
          if (serverSideEncryption) {
            meta.setHeader(Headers.SERVER_SIDE_ENCRYPTION, SSEAlgorithm.AES256.getAlgorithm)
          }

          client.putObject(new PutObjectRequest(bucket, key, src).withMetadata(meta))

      }
    }

    // Do we know for sure that this bucket requires SSE?
    val requiresSSE: Boolean = bucketRequiresSSE.containsKey(bucket)

    if ((options.s3sse || options.kmsCustomerMasterKeyID.isDefined ) || requiresSSE) {
      // We know we require SSE
      putImpl(serverSideEncryption = true, options.kmsCustomerMasterKeyID)
    } else {
      try {
        // We either don't require SSE or don't know yet so we try without SSE enabled
        putImpl(serverSideEncryption = false)
      } catch {
        case ex: AmazonS3Exception if ex.getStatusCode == 403 =>
          options.log.debug(
            s"upload($src, $dest) failed with a 403 status code. Retrying with Server Side Encryption Enabled."
          )

          // Retry with SSE
          val res: PutObjectResult = putImpl(serverSideEncryption = true)

          // If that succeeded then save the fact that we require SSE for future requests
          bucketRequiresSSE.put(bucket, true)

          options.log.info(s"S3URLHandler - Enabled Server Side Encryption (SSE) for bucket: $bucket")

          res
      }
    }

    if (null != l) l.end(event)
  }

  // I don't think we care what this is set to
  def setRequestMethod(requestMethod: Int): Unit = options.log.debug(s"setRequestMethod($requestMethod)")

  // Try to get the region of the S3 URL so we can set it on the S3Client
  def getRegion(url: URL, bucket: String, client: => AmazonS3): Regions = {

    val region: Option[String] = getRegionNameFromURL(url)
      .orElse(options.s3region.map(_.toString))
      .orElse(getRegionNameFromDNS(bucket))
      .orElse(getRegionNameFromService(bucket, client))
      .orElse(Option(Regions.getCurrentRegion).map(_.getName))

    region.map(Regions.fromName).flatMap(Option(_)).getOrElse(Regions.DEFAULT_REGION)
  }

  def getRegionNameFromURL(url: URL): Option[String] = {
    // We'll try the AmazonS3URI parsing first then fallback to our RegionMatcher
    getAmazonS3URI(url).map(_.getRegion).flatMap(Option(_)).orElse(RegionMatcher.findFirstIn(url.toString))
  }
  
  def getRegionNameFromDNS(bucket: String): Option[String] = {
    // This gives us something like s3-us-west-2-w.amazonaws.com which must have changed
    // at some point because the region from that hostname is no longer parsed by AmazonS3URI
    val canonicalHostName: String = InetAddress.getByName(bucket + ".s3.amazonaws.com").getCanonicalHostName
    
    // So we use our regex based RegionMatcher to try and extract the region since AmazonS3URI doesn't work
    RegionMatcher.findFirstIn(canonicalHostName)
  }

  def getRegionNameFromService(bucket: String, client: AmazonS3): Option[String] = {
    // This might fail if the current credentials don't have access to the getBucketLocation call
    Try { client.getBucketLocation(bucket) }.map {
      case "US" => "us-east-1"
      case value => value
    }.toOption
  }

  def getBucketAndKey(url: URL): (String, String) = {
    // The AmazonS3URI constructor should work for standard S3 urls.  But if a custom domain is being used
    // (e.g. snapshots.maven.frugalmechanic.com) then we treat the hostname as the bucket and the path as the key
    getAmazonS3URI(url).map{ amzn: AmazonS3URI =>
      (amzn.getBucket, amzn.getKey)
    }.getOrElse {
      // Probably a custom domain name - The host should be the bucket and the path the key
      (url.getHost, url.getPath.stripPrefix("/"))
    }
  }
  
  def getAmazonS3URI(uri: String): Option[AmazonS3URI] = getAmazonS3URI(URI.create(uri))
  def getAmazonS3URI(url: URL)   : Option[AmazonS3URI] = getAmazonS3URI(url.toURI)
  
  def getAmazonS3URI(uri: URI)   : Option[AmazonS3URI] = try {
    val httpsURI: URI =
      // If there is no scheme (e.g. new URI("s3-us-west-2.amazonaws.com/<bucket>"))
      // then we need to re-create the URI to add one and to also make sure the host is set
      if (uri.getScheme == null) new URI("https://"+uri)
      // AmazonS3URI can't parse the region from s3:// URLs so we rewrite the scheme to https://
      else new URI("https", uri.getUserInfo, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment)

    Some(new AmazonS3URI(httpsURI))
  } catch {
    case _: IllegalArgumentException => None
  }
}

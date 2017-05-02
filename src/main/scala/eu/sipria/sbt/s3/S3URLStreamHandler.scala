package eu.sipria.sbt.s3

import java.net.{URL, URLConnection, URLStreamHandler}

/**
 * For normal SBT usage this is a dummy URLStreamHandler so that s3:// URLs can be created without throwing a
 * java.net.MalformedURLException.  However for something like Coursier (https://github.com/coursier/coursier)
 * this needs to be implemented since it doesn't use the normal SBT resolving mechanisms.
 */
final class S3URLStreamHandler(options: S3ResolverOptions) extends URLStreamHandler {
  def openConnection(url: URL): URLConnection = new S3URLConnection(url, options)
}

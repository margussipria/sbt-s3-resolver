package eu.sipria.sbt.s3

import java.net.{URL, URLStreamHandler, URLStreamHandlerFactory}

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.profile.internal.AwsProfileNameLoader
import com.amazonaws.services.s3.model.Region
import org.apache.ivy.util.url.{URLHandlerDispatcher, URLHandlerRegistry}
import sbt.Keys.{state, _}
import sbt._
import sbt.complete.DefaultParsers._

import scala.util.Try

/**
 * All this does is register the s3:// url handler with the JVM and IVY
 */
object S3ResolverPlugin extends AutoPlugin {
  object autoImport {
    lazy val awsProfile: SettingKey[String] = SettingKey[String]("awsProfile", "AWS credentials profile")

    // This can be set for publishing, but should not set for fetching from multiple different s3 buckets
    lazy val s3region: SettingKey[Option[Region]] = {
      SettingKey[Option[Region]]("s3region", "AWS Region for your S3 resolvers")
    }

    lazy val s3sse: SettingKey[Boolean] = {
      SettingKey[Boolean]("s3sse", "Controls whether publishing resolver will use server side encryption")
    }

    lazy val kmsCustomerMasterKeyID: SettingKey[Option[String]] = {
      SettingKey[Option[String]]("kmsCustomerMasterKeyID", "Custom KMS Customer master Key ID for SSE")
    }

    lazy val s3CredentialsProvider: SettingKey[String => AWSCredentialsProvider] = {
      SettingKey[String => AWSCredentialsProvider]("s3CredentialsProvider", "AWS credentials provider to access S3")
    }

    lazy val showS3Credentials: InputKey[Unit] = {
      InputKey[Unit]("showS3Credentials", "Just outputs credentials that are loaded by the s3CredentialsProvider")
    }
  }

  import autoImport._

  // This plugin will load automatically
  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Setting[_]] = Seq(
    awsProfile := AwsProfileNameLoader.DEFAULT_PROFILE_NAME,

    s3region := None,

    s3sse := false,

    kmsCustomerMasterKeyID := None,

    s3CredentialsProvider := { (bucket: String) =>
      new AWSCredentialsProviderChain(
        new EnvironmentVariableCredentialsProvider,
        new SystemPropertiesCredentialsProvider,
        new ProfileCredentialsProvider(awsProfile.value),
        new EC2ContainerCredentialsProviderWrapper,
        new ProfileCredentialsProvider(bucket)
      )
    },
    showS3Credentials := {
      val log = state.value.log

      spaceDelimited("<arg>").parsed match {
        case bucket :: Nil =>
          val provider: AWSCredentialsProvider = s3CredentialsProvider.value(bucket)

          Try {
            val awsCredentials = Option(provider.getCredentials).getOrElse {
              throw new Exception("Couldn't find credentials from provider for bucked: %s" format bucket)
            }

            log.info("Found following AWS credentials:")
            log.info("Access key: " + awsCredentials.getAWSAccessKeyId)
            log.info("Secret key: " + awsCredentials.getAWSSecretKey)

          } recover { case e: Exception =>
            log.error(e.getMessage)
          }

        case Nil =>
          log.error("Bucket name not given")

        case _ =>
          log.error("Too many arguments for showS3Credentials")
      }
    },
    onLoad in Global := (onLoad in Global).value andThen { state =>
      def info: String => Unit = state.log.info(_)

      val options = S3ResolverOptions(
        s3CredentialsProvider.value,

        s3region.value,
        s3sse.value,
        kmsCustomerMasterKeyID.value,

        state.log
      )

      // We need s3:// URLs to work without throwing a java.net.MalformedURLException
      // which means installing a dummy URLStreamHandler.  We only install the handler
      // if it's not already installed (since a second call to URL.setURLStreamHandlerFactory
      // will fail).
      try {
        new URL("s3://example.com")
        info("The s3:// URLStreamHandler is already installed")
      } catch {
        // This means we haven't installed the handler, so install it
        case _: java.net.MalformedURLException =>
          info("Installing the s3:// URLStreamHandler via java.net.URL.setURLStreamHandlerFactory")
          URL.setURLStreamHandlerFactory(new S3URLStreamHandlerFactory(options))
      }

      // This sets up the Ivy URLHandler for s3:// URLs
      val dispatcher: URLHandlerDispatcher = URLHandlerRegistry.getDefault match {
        // If the default is already a URLHandlerDispatcher then just use that
        case dispatcher: URLHandlerDispatcher =>
          info("Using the existing Ivy URLHandlerDispatcher to handle s3:// URLs")
          dispatcher
        // Otherwise create a new URLHandlerDispatcher
        case default =>
          info("Creating a new Ivy URLHandlerDispatcher to handle s3:// URLs")
          val dispatcher: URLHandlerDispatcher = new URLHandlerDispatcher()
          dispatcher.setDefault(default)
          URLHandlerRegistry.setDefault(dispatcher)
          dispatcher
      }

      // Register (or replace) the s3 handler
      dispatcher.setDownloader("s3", new S3URLHandler(options))

      state
    }
  )

  private class S3URLStreamHandlerFactory(options: S3ResolverOptions) extends URLStreamHandlerFactory {
    def createURLStreamHandler(protocol: String): URLStreamHandler = protocol match {
      case "s3" => new S3URLStreamHandler(options)
      case _    => null
    }
  }
}

package eu.sipria.sbt.s3

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.s3.model.Region

case class S3ResolverOptions(
  credentialsProvider: String => AWSCredentialsProvider,

  s3region: Option[Region],
  s3sse: Boolean,
  kmsCustomerMasterKeyID: Option[String],

  log: sbt.Logger
)

# SBT S3 Resolver

This SBT plugin adds support for using Amazon S3 for resolving and publishing using s3:// urls.

## Table of Contents

  * [Example](#example)
  * [Usage](#usage)
  * [IAM Policy Examples](#iam)
  * [IAM Role Examples](#iam-role)
  * [S3 Server-Side Encryption](#server-side-encryption)
  * [License](#license)

## <a name="example"></a>Example

### Resolving Dependencies via S3

Maven Style:

```scala
resolvers += "Example Snapshots" at "s3://maven.example.com/snapshots"
```

Ivy Style:

```scala
resolvers += Resolver.url("Example Snapshots", url("s3://maven.example.com/snapshots"))(Resolver.ivyStylePatterns)
```

### Publishing to S3

Maven Style:

```scala
publishMavenStyle := true
publishTo := Some("Example Snapshots" at "s3://maven.example.com/snapshots")
```

Ivy Style:

    publishMavenStyle := false
    publishTo := Some("Example Snapshots" at "s3://maven.example.com/snapshots")

### Valid s3:// URL Formats

The examples above are using the [Static Website Using a Custom Domain](http://docs.aws.amazon.com/AmazonS3/latest/dev/website-hosting-custom-domain-walkthrough.html) functionality of S3.

These would also be equivalent (for the **maven.example.com** bucket):

    s3://s3-us-west-2.amazonaws.com/maven.example.com/snapshots
    s3://maven.example.com.s3-us-west-2.amazonaws.com/snapshots
    s3://maven.example.com.s3.amazonaws.com/snapshots
    s3://s3.amazonaws.com/maven.example.com/snapshots

All of these forms should work:

    s3://[BUCKET]/[OPTIONAL_PATH]
    s3://s3.amazonaws.com/[BUCKET]/[OPTIONAL_PATH]
    s3://[BUCKET].s3.amazonaws.com/[OPTIONAL_PATH]
    s3://s3-[REGION].amazonaws.com/[BUCKET]/[OPTIONAL_PATH]
    s3://[BUCKET].s3-[REGION].amazonaws.com/[OPTIONAL_PATH]

## <a name="usage"></a>Usage

### Plugin sbt dependency

In `project/plugins.sbt`:

```scala
addSbtPlugin("eu.sipria.sbt" % "sbt-s3-resolver" % "0.12.0")
```

For SBT Plugins use `project/project/plugins.sbt`

### Setting keys

* `awsProfile`: AWS credentials profile (for default `s3credentials` implementation)
* `s3region`: AWS Region for your S3 resolvers
* `s3sse`: Controls whether publishing resolver will use server side encryption (can be forced with policy)
* `kmsCustomerMasterKeyID`: Custom KMS Customer master Key ID for SSE
* `s3CredentialsProvider`: AWS credentials provider to access S3

|                      Key | Type                               | Default                   |
|-------------------------:|:----------------------------------:|:--------------------------|
|             `awsProfile` | `String`                           | `"default"`               |
|               `s3region` | `Option[Region]`                   | `None`                    |
|                  `s3sse` | `Boolean`                          | `false`                   |
| `kmsCustomerMasterKeyID` | `Option[String]`                   | `None`                    |
|  `s3CredentialsProvider` | `String => AWSCredentialsProvider` |                           |

```scala
import com.amazonaws.services.s3.model.Region
import com.amazonaws.auth.AWSCredentialsProvider
```

### S3 Credentials

S3 Credentials are checked **in the following places and _order_**:

Default implementation for `s3CredentialsProvider`:

```scala
s3CredentialsProvider := { (bucket: String) =>
  new AWSCredentialsProviderChain(
    new EnvironmentVariableCredentialsProvider,
    new SystemPropertiesCredentialsProvider,
    new ProfileCredentialsProvider(awsProfile.value),
    new EC2ContainerCredentialsProviderWrapper,
    new ProfileCredentialsProvider(bucket)
  )
    },
```

#### Environment Variables

```shell
AWS_ACCESS_KEY_ID (or AWS_ACCESS_KEY)
AWS_SECRET_KEY (or AWS_SECRET_ACCESS_KEY)
AWS_ROLE_ARN
```

Example:

```shell
# Basic Credentials
AWS_ACCESS_KEY_ID="XXXXXX" AWS_SECRET_KEY="XXXXXX" sbt

# IAM Role Credentials
AWS_ACCESS_KEY_ID="XXXXXX" AWS_SECRET_KEY="XXXXXX" AWS_ROLE_ARN="arn:aws:iam::123456789012:role/RoleName" sbt
```


#### Java System Properties

```shell
// Basic Credentials
-Daws.accessKeyId=XXXXXX -Daws.secretKey=XXXXXX 
 
// IAM Role
-Daws.accessKeyId=XXXXXX -Daws.secretKey=XXXXXX -Daws.arnRole=arn:aws:iam::123456789012:role/RoleName
```
 
Example:
 
```shell
# Basic Credentials
SBT_OPTS="-Daws.accessKeyId=XXXXXX -Daws.secretKey=XXXXXX" sbt
 
# IAM Role Credentials
SBT_OPTS="-Daws.accessKeyId=XXXXXX -Daws.secretKey=XXXXXX -Daws.arnRole=arn:aws:iam::123456789012:role/RoleName" sbt
```
 
## <a name="iam"></a>IAM Policy Examples

I recommend that you create IAM Credentials for reading/writing your Maven S3 Bucket.  



Here are some examples for **maven.example.com** bucket:

### Publishing Policy (Read/Write)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation"],
      "Resource": "arn:aws:s3:::*"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": ["arn:aws:s3:::maven.example.com"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:DeleteObject","s3:GetObject","s3:PutObject"],
      "Resource": ["arn:aws:s3:::maven.example.com/*"]
    }
  ]
}
```

### Read-Only Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation"],
      "Resource": "arn:aws:s3:::*"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": ["arn:aws:s3:::maven.example.com"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::maven.example.com/*"]
    }
  ]
}
```

### Releases Read-Only with Snapshots Publishing (Read/Write)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation"],
      "Resource": "arn:aws:s3:::*"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": ["arn:aws:s3:::maven.example.com"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::maven.example.com/releases/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:DeleteObject","s3:GetObject","s3:PutObject"],
      "Resource": ["arn:aws:s3:::maven.example.com/snapshots/*"]
    }
  ]
}
```

## <a name="iam-role"></a>IAM Role Policy Examples

This is a simple example where a Host AWS Account, can create a Role with permissions for a Client AWS Account to access the Host maven bucket.

  1. Host AWS Account, creates an IAM Role named "ClientAccessRole" with policy:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::[Client AWS Account Id]:user/[Client User Name]"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

  2. Associate the proper [IAM Policy Examples](#iam) to the Host Role
  3. Client AWS Account needs to create an AWS IAM User [Client User Name] and associated a policy to gives it permissions to AssumeRole from the Host AWS Account:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::[Host AWS Account Id]:role/ClientAccessRole"
    }
  ]
}
```

## <a name="server-side-encryption"></a>S3 Server-Side Encryption
S3 supports <a href="http://docs.aws.amazon.com/AmazonS3/latest/dev/UsingServerSideEncryption.html">server side encryption</a>.
The plugin will automatically detect if it needs to ask S3 to use SSE, based on the policies you have on your bucket. If
your bucket denies `PutObject` requests that aren't using SSE, the plugin will include the SSE header in future requests.

To make use of SSE, configure your bucket to enforce the SSE header for `PutObject` requests.

Example:
```json
{
  "Version": "2012-10-17",
  "Id": "PutObjPolicy",
  "Statement": [
    {
      "Sid": "DenyIncorrectEncryptionHeader",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::YOUR_BUCKET_HERE/*",
      "Condition": {
        "StringNotEquals": {
          "s3:x-amz-server-side-encryption": "AES256"
        }
      }
    },
    {
      "Sid": "DenyUnEncryptedObjectUploads",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::YOUR_BUCKET_HERE/*",
      "Condition": {
        "Null": {
          "s3:x-amz-server-side-encryption": "true"
        }
      }
    }
  ]
}
```

## <a name="license"></a>License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)
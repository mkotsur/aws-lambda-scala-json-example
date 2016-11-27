package io.github.mkotsur.aws.handler

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8

import com.amazonaws.services.lambda.runtime.Context
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.github.mkotsur.aws.proxy.{ProxyRequest, ProxyResponse}

import scala.io.Source

object LambdaHandler {

  type ReadStream[I] = InputStream => Either[Throwable, I]

  type ObjectHandler[I, O] = I => Either[Throwable, O]

  type WriteStream[O] = (OutputStream, O, Context) => Unit

  /**
    * The implementation of 2 following methods should most definitely be rewritten for it's ugly as sin.
    */
  object proxy {

    type ProxyRequest$String = ProxyRequest[String]
    type ProxyResponse$String = ProxyResponse[String]

    implicit def canDecodeProxyRequest[T](implicit decoderT: CanDecode[T]) = CanDecode.instance[ProxyRequest[T]] {
      is => {

        val eitherPRS: Either[Throwable, ProxyRequest$String] = decode[ProxyRequest$String](Source.fromInputStream(is).mkString)

        eitherPRS.flatMap(prs => {

          val optionEither = prs.body
            .map(bodyString => new ByteArrayInputStream(bodyString.getBytes))
            .map(decoderT.readStream)

          val bodyEitherOption: Either[Throwable, Option[T]] = optionEither match {
            case None => Right(None)
            case Some(Left(l)) => Left(l)
            case Some(Right(v)) => Right[Throwable, Option[T]](Some(v))
          }

          val newEither: Either[Throwable, ProxyRequest[T]] = bodyEitherOption.flatMap(bo => Right(ProxyRequest[T](
            prs.path,
            prs.httpMethod,
            prs.headers,
            prs.queryStringParameters,
            prs.stageVariables,
            bo,
            prs.requestContext
          )))

          newEither
        })

      }
    }

    implicit def canEncodeProxyResponse[T](implicit canEncode: Encoder[T]) = CanEncode.instance[ProxyResponse[T]](
      (output, proxyResponse, context) => {
        val encodedBodyOption = proxyResponse.body.map(bodyObject => bodyObject.asJson.noSpaces)

        output.write(
          ProxyResponse[String](
            proxyResponse.statusCode, proxyResponse.headers,
            encodedBodyOption
          ).asJson.noSpaces.getBytes
        )
      }
    )
  }

  object string {
    implicit def canDecodeString = CanDecode.instance(
      is => Right(Source.fromInputStream(is).mkString)
    )

    implicit def canEncodeString = CanEncode.instance[String](
      (output, s, context) => output.write(s.getBytes)
    )
  }

  implicit def canDecodeCaseClasses[T](implicit decoder: Decoder[T]) = CanDecode.instance(
    is => decode[T](Source.fromInputStream(is).mkString)
  )

  implicit def canEncodeCaseClasses[T](implicit encoder: Encoder[T]) = CanEncode.instance[T](
    (output, o, _) => {
      val jsonString = o.asJson.noSpaces
      output.write(jsonString.getBytes(UTF_8))
    }
  )

}

abstract class LambdaHandler[I, O](implicit canDecode: CanDecode[I], canEncode: CanEncode[O]) {

  private type StreamHandler = (InputStream, OutputStream, Context) => Unit

  // This method should be overriden
  protected def handle(i: I): Either[Throwable, O]

  // This function will ultimately be used as the external handler
  final def handle(input: InputStream, output: OutputStream, context: Context): Unit = {
    canDecode.readStream(input).flatMap(handle) match {
      case Right(handled) =>
        canEncode.writeStream(output, handled, context)
      case Left(e) =>
        throw e
    }

    output.close()
  }

}

package com.gh.dobrynya.md5checker

import java.security.MessageDigest
import zio._
import console._
import stream._
import blocking.Blocking

object Md5Checker extends App {
  type MyEnv = Has[HttpClient] with Blocking with Console

  val env = HttpClient.live ++ Console.live ++ Blocking.live

  def bytes2strings[R, E](bytes: ZStream[R, E, Chunk[Byte]]): ZStream[R, E, String] =
    ZStreamChunk(bytes.transduce[R, E, Chunk[Byte], String](Sink.utf8DecodeChunk).transduce(Sink.splitLines))
      .flattenChunks

  def readFileDescriptions(url: String): ZIO[MyEnv, Throwable, List[String]] =
    for {
      _ <- console.putStrLn(s"Reading files URLs to check MD5 hash from $url")
      files <- ZIO.accessM[MyEnv](r => bytes2strings(r.get.httpClient.download(url)).runCollect)
      _ <- console.putStrLn(s"It needs to check the following files $files")
    } yield files

  def md5Hash[R] =
    ZSink.foldLeft[Chunk[Byte], MessageDigest](MessageDigest.getInstance("MD5")) { (hasher, chunk) =>
      hasher.update(chunk.toArray)
      hasher
    }.map(_.digest().foldLeft("")((acc, byte) => s"$acc${String.format("%02x", byte)}"))

  def printInfo(d: FileDescription) =
    d match {
      case FileDescription(url, md5, Some(calculatedMd5), None) if md5 != calculatedMd5 =>
        s"File at $url with hash $md5, calculated hash $calculatedMd5 does not conform the provided MD5 hash"
      case FileDescription(url, md5, Some(_), None) =>
        s"File at $url with hash $md5, calculated hash is equal provided hash"
      case FileDescription(url, md5, _, Some(error)) =>
        s"There is an error '$error' during processing a file at $url with provided hash $md5"
    }

  def calculateMd5(description: FileDescription): RIO[MyEnv, String] =
    for {
      http <- ZIO.access[MyEnv](_.get.httpClient)
      line <- (if (description.valid) http.download(description.url).run(md5Hash)
        .map(md5 => description.copy(calculatedMd5 = Some(md5)))
        .catchAll(th =>
          console.putStrLn(s"An error occurred $th!") *>
            ZIO.effectTotal(description.copy(error = Some(s"Error: ${th.getMessage}"))))
      else ZIO.succeed(description)).map(printInfo)
      _ <- putStrLn(line)
    } yield line

  val url = "file:urls.txt"

  val program =
    for {
      list <- readFileDescriptions(url).map(_.map(FileDescription.of))
      _ <- ZIO.collectAllParN(4)(list.map(calculateMd5))
    } yield ()

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    program.provideLayer(env).fold(_ => 0, _ => 0)
//      .catchAll(th => ZIO.effectTotal(th.printStackTrace())).as(0)
}

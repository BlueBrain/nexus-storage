package ch.epfl.bluebrain.nexus.storage

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.Compression.gzip
import akka.stream.scaladsl.{FileIO, Keep, Sink}
import akka.util.ByteString
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File._
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathContainsSymlinks, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, PathInvalid}
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.{BucketExistence, PathExistence}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.StorageConfig
import com.github.ghik.silencer.silent
import java.nio.file.StandardCopyOption._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait Storages[Source] {

  /**
    * Checks that the provided bucket name exists and it is readable/writable.
    *
    * @param name the storage bucket name
    */
  def exists(name: String): BucketExistence

  /**
    * Check whether the provided path already exists.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path location
    */
  def pathExists(name: String, relativePath: Uri.Path): PathExistence

  /**
    * Creates a file with the provided ''metadata'' and ''source'' on the provided ''filePath''.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path location
    * @param source   the file content
    * @return Left(rejection) or Right(fileAttributes).
    *         The file attributes contains the metadata plus the file bytes, digest and location
    */
  def createFile[F[_]: Effect](name: String, relativePath: Uri.Path, source: Source)(
      implicit @silent bucketEv: BucketExists,
      @silent pathEv: PathDoesNotExist): F[FileAttributes]

  /**
    * Moves a path from the provided ''sourceRelativePath'' to ''destRelativePath'' inside the nexus folder.
    *
    * @param name               the storage bucket name
    * @param sourceRelativePath the source relative path location
    * @param destRelativePath   the destination relative path location inside the nexus folder
    * @return Left(rejection) or Right(fileAttributes).
    *         The file attributes contains the metadata plus the file bytes, digest and location
    */
  def moveFile[F[_]: Effect](name: String, sourceRelativePath: Uri.Path, destRelativePath: Uri.Path)(
      implicit @silent bucketEv: BucketExists): F[RejOrAttributes]

  /**
    * Retrieves the file as a Source.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path to the file location
    * @return Left(rejection),  Right(source, Some(filename)) when the path is a file and Right(source, None) when the path is a directory
    */
  def getFile(name: String, relativePath: Uri.Path)(implicit @silent bucketEv: BucketExists,
                                                    @silent pathEv: PathExists): RejOr[(Source, Option[String])]

}

object Storages {

  sealed trait BucketExistence
  sealed trait PathExistence

  object BucketExistence {
    final case object BucketExists       extends BucketExistence
    final case object BucketDoesNotExist extends BucketExistence
    type BucketExists       = BucketExists.type
    type BucketDoesNotExist = BucketDoesNotExist.type
  }

  object PathExistence {
    final case object PathExists       extends PathExistence
    final case object PathDoesNotExist extends PathExistence
    type PathExists       = PathExists.type
    type PathDoesNotExist = PathDoesNotExist.type
  }

  /**
    * An Disk implementation of Storage interface.
    */
  final class DiskStorage(config: StorageConfig)(implicit ec: ExecutionContext, mt: Materializer)
      extends Storages[AkkaSource] {

    private def basePath(name: String, protectedDir: Boolean = true): Path = {
      val path = config.rootVolume.resolve(name).normalize()
      if (protectedDir) path.resolve(config.protectedDirectory).normalize() else path
    }

    private def filePath(name: String, relativePath: Uri.Path, protectedDir: Boolean = true): Path =
      basePath(name, protectedDir).resolve(Paths.get(relativePath.toString())).normalize()

    def exists(name: String): BucketExistence = {
      val path = basePath(name)
      if (path.getParent.getParent != config.rootVolume) BucketDoesNotExist
      else if (Files.isDirectory(path) && Files.isWritable(path) && Files.isReadable(path)) BucketExists
      else BucketDoesNotExist
    }

    def pathExists(name: String, relativeFilePath: Uri.Path): PathExistence = {
      val path = filePath(name, relativeFilePath)
      if (Files.exists(path) && Files.isReadable(path) && path.descendantOf(basePath(name))) PathExists
      else PathDoesNotExist
    }

    def createFile[F[_]](name: String, relativeFilePath: Uri.Path, source: AkkaSource)(
        implicit F: Effect[F],
        @silent bucketEv: BucketExists,
        @silent pathEv: PathDoesNotExist): F[FileAttributes] = {
      val absFilePath = filePath(name, relativeFilePath)
      if (absFilePath.descendantOf(basePath(name)))
        F.fromTry(Try(Files.createDirectories(absFilePath.getParent))) >>
          source
            .alsoToMat(digestSink(config.algorithm))(Keep.right)
            .toMat(FileIO.toPath(absFilePath)) {
              case (digFuture, ioFuture) =>
                digFuture.zipWith(ioFuture) {
                  case (digest, io) if io.wasSuccessful && absFilePath.toFile.exists() =>
                    Future(FileAttributes(s"file://$absFilePath", io.count, digest))
                  case _ =>
                    Future.failed(InternalError(s"I/O error writing file to path '$relativeFilePath'"))
                }
            }
            .run()
            .flatten
            .to[F]
      else
        F.raiseError(PathInvalid(name, relativeFilePath))
    }

    def moveFile[F[_]](name: String, sourceRelativePath: Uri.Path, destRelativePath: Uri.Path)(
        implicit F: Effect[F],
        bucketEv: BucketExists): F[RejOrAttributes] = {

      val bucketPath          = basePath(name, protectedDir = false)
      val bucketProtectedPath = basePath(name)
      val absSourcePath       = filePath(name, sourceRelativePath, protectedDir = false)
      val absDestPath         = filePath(name, destRelativePath)

      def computeMetadata(source: AkkaSource): F[RejOrAttributes] =
        source
          .toMat(digestSink(config.algorithm))(Keep.right)
          .run()
          .map[RejOrAttributes] { digest =>
            Right(FileAttributes(s"file://$absDestPath", Files.size(absDestPath), digest))
          }
          .to[F]

      //TODO: When the resource that we want to move is being used by some process, a copy will be issue instead: https://stackoverflow.com/questions/34733765/java-nio-file-files-move-is-copying-instead-of-moving
      def moveAndComputeMeta(sourceF: Path => AkkaSource): F[RejOrAttributes] =
        F.fromTry(Try(Files.createDirectories(absDestPath.getParent))) >>
          F.fromTry(Try(Files.move(absSourcePath, absDestPath, ATOMIC_MOVE))) >>
          computeMetadata(sourceF(absDestPath))

      def dirContainsSymbolicLink(path: Path): F[Boolean] =
        Directory.walk(path).map(Files.isSymbolicLink).takeWhile(_ == false, inclusive = true).runWith(Sink.last).to[F]

      if (!Files.exists(absSourcePath) || !Files.isWritable(absSourcePath))
        F.pure(Left(PathNotFound(name, sourceRelativePath)))
      else if (!absSourcePath.descendantOf(bucketPath) || absSourcePath.descendantOf(bucketProtectedPath))
        F.pure(Left(PathNotFound(name, sourceRelativePath)))
      else if (!absDestPath.descendantOf(bucketProtectedPath))
        F.raiseError(PathInvalid(name, destRelativePath))
      else if (Files.exists(absDestPath))
        F.pure(Left(PathAlreadyExists(name, destRelativePath)))
      else if (Files.isSymbolicLink(absSourcePath))
        F.pure(Left(PathContainsSymlinks(name, sourceRelativePath)))
      else if (Files.isRegularFile(absSourcePath))
        moveAndComputeMeta(getFile)
      else if (Files.isDirectory(absSourcePath))
        dirContainsSymbolicLink(absSourcePath).flatMap {
          case true  => F.pure(Left(PathContainsSymlinks(name, sourceRelativePath)))
          case false => moveAndComputeMeta(getDirectory)
        } else F.pure(Left(PathNotFound(name, sourceRelativePath)))
    }

    def getFile(name: String, relativePath: Uri.Path)(
        implicit @silent bucketEv: BucketExists,
        @silent pathEv: PathExists): RejOr[(AkkaSource, Option[String])] = {
      val absPath = filePath(name, relativePath)
      if (Files.isRegularFile(absPath)) Right(getFile(absPath) -> Some(absPath.getFileName.toString))
      else if (Files.isDirectory(absPath)) Right(getDirectory(absPath) -> None)
      else Left(PathNotFound(name, relativePath))
    }

    private def getDirectory(absPath: Path): AkkaSource = Directory.walk(absPath).via(TarFlow.writer(absPath)).via(gzip)
    private def getFile(absPath: Path): AkkaSource      = FileIO.fromPath(absPath)

    private def digestSink(algorithm: String): Sink[ByteString, Future[Digest]] =
      Sink
        .fold(MessageDigest.getInstance(algorithm))((digest, currentBytes: ByteString) => {
          digest.update(currentBytes.asByteBuffer)
          digest
        })
        .mapMaterializedValue(_.map(dig => Digest(dig.getAlgorithm, dig.digest().map("%02x".format(_)).mkString)))
  }
}

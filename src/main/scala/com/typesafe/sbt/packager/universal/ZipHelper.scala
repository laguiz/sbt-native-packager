package com.typesafe.sbt
package packager
package universal

import sbt._
import java.io._
import java.util.zip._
import org.apache.commons.compress.archivers.zip._
import org.apache.commons.compress.compressors.{
  CompressorStreamFactory,
  CompressorOutputStream
}
import java.util.zip.Deflater
import org.apache.commons.compress.utils.IOUtils

object ZipHelper {
  case class FileMapping(file: File, name: String, unixMode: Option[Int] = None)

  /**
   * Creates a zip file attempting to give files the appropriate unix permissions using Java 6 APIs.
   * @param sources   The files to include in the zip file.
   * @param outputZip The location of the output file.
   */
  def zipNative(sources: Traversable[(File, String)], outputZip: File): Unit =
    IO.withTemporaryDirectory { dir =>
      val name = outputZip.getName
      val zipDir = dir / (if (name endsWith ".zip") name dropRight 4 else name)
      val files = for {
        (file, name) <- sources
      } yield file -> (zipDir / name)
      IO.copy(files)
      for {
        (src, target) <- files
        if src.canExecute
      } target.setExecutable(true, true)
      val dirFileNames = Option(zipDir.listFiles) getOrElse Array.empty[java.io.File] map (_.getName)
      Process(Seq("zip", "-r", name) ++ dirFileNames, zipDir).! match {
        case 0 => ()
        case n => sys.error("Failed to run native zip application!")
      }

      IO.copyFile(zipDir / name, outputZip)
    }

  /**
   * Creates a zip file attempting to give files the appropriate unix permissions using Java 6 APIs.
   * Note: This is known to have some odd issues on MacOSX whereby executable permissions
   * are not actually discovered, even though the Info-Zip headers exist and work on
   * many variants of linux.  Yay Apple.
   * @param sources   The files to include in the zip file.
   * @param outputZip The location of the output file.
   */
  def zip(sources: Traversable[(File, String)], outputZip: File): Unit = {
    val mappings =
      for {
        (file, name) <- sources.toSeq
        // TODO - Figure out if this is good enough....
        perm = if (file.isDirectory || file.canExecute) 0755 else 0644
      } yield FileMapping(file, name, Some(perm))
    archive(mappings, outputZip)
  }

  /**
   * Creates a zip file using the given set of filters
   * @param sources   The files to include in the zip file.  A File, Location, Permission pairing.
   * @param outputZip The location of the output file.
   */
  def zipWithPerms(sources: Traversable[(File, String, Int)], outputZip: File): Unit = {
    val mappings =
      for {
        (file, name, perm) <- sources
      } yield FileMapping(file, name, Some(perm))
    archive(mappings.toSeq, outputZip)
  }

  /**
   * Add files to an existing zip
   * @param zipFile The zip to modify
   * @param files files to include in the zip file (File, Location, Target Name).
   */
  def addFilesToExistingZip(zipFile: File, files: Seq[(File, String, String)]) = {

    //Read zip data and create a ZipInputStream (used to read existing entries and copy into new version of the ZIP)
    //Load in memory so no conflict with existing file
    val zin = new ZipInputStream(new ByteArrayInputStream(IO.readBytes(zipFile)));
    //Create ZipOutputStream based on exiting file
    val out = new ZipOutputStream(new FileOutputStream(zipFile));

    //We copy existing entries based on in memory read zip
    // Better way? I followed http://www.dzone.com/snippets/adding-files-existing-jar-file.
    // I tried multiple things to avoid it but it seems that the only way is to copy entries
    var entry = zin.getNextEntry();
    while (entry != null) {
      val originalName = ZipHelper.normalizePath(entry.getName());
      //Found double `//` in path that create folder named `_` when we extract zip on Windows.
      // For example it appen in `conf` folder for Play projects.
      // This is an hack only. It must be fixed before we reach this tasks. Maybe when we are doing the mapping...
      val name = originalName.replace("//", "/");
      out.putNextEntry(new ZipEntry(name));
      out.write(IO.readBytes(zin))
      entry = zin.getNextEntry();
    }

    // Close ZipInputStream
    zin.close();

    // Add new files
    files map {
      case (file, path, name) => {
        out.putNextEntry(new ZipEntry(ZipHelper.normalizePath(path + name)));
        out.write(IO.readBytes(file))
        out.closeEntry();
      }
    }

    // Close ZipOutputStream
    out.close();

  }

  /**
   * Replaces windows backslash file separator with a forward slash, this ensures the zip file entry is correct for
   * any system it is extracted on.
   * @param path  The path of the file in the zip file
   */
  private def normalizePath(path: String) = {
    val sep = java.io.File.separatorChar
    if (sep == '/')
      path
    else
      path.replace(sep, '/')
  }

  private def archive(sources: Seq[FileMapping], outputFile: File): Unit = {
    if (outputFile.isDirectory) sys.error("Specified output file " + outputFile + " is a directory.")
    else {
      val outputDir = outputFile.getParentFile
      IO createDirectory outputDir
      withZipOutput(outputFile) { output =>
        for (FileMapping(file, name, mode) <- sources; if !file.isDirectory) {
          val entry = new ZipArchiveEntry(file, normalizePath(name))
          // Now check to see if we have permissions for this sucker.
          mode foreach (entry.setUnixMode)
          output putArchiveEntry entry
          // TODO - Write file into output?
          IOUtils.copy(new java.io.FileInputStream(file), output)
          output.closeArchiveEntry()
        }
      }
    }
  }

  private def withZipOutput(file: File)(f: ZipArchiveOutputStream => Unit): Unit = {
    val zipOut = new ZipArchiveOutputStream(file)
    zipOut setLevel Deflater.BEST_COMPRESSION
    try { f(zipOut) }
    finally {
      zipOut.close()
    }
  }
}

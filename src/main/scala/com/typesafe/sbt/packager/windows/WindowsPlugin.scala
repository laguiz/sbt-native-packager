package com.typesafe.sbt
package packager
package windows

import Keys._
import sbt._
import sbt.Keys.{ normalizedName }
import com.typesafe.sbt.packager.universal.UniversalPlugin
import com.typesafe.sbt.packager.universal.ZipHelper

trait WindowsPlugin extends Plugin with UniversalPlugin {

  val Windows = config("windows")

  def windowsSettings: Seq[Setting[_]] = Seq(
    sourceDirectory in Windows <<= sourceDirectory(_ / "windows"),
    target in Windows <<= target apply (_ / "windows"),
    // TODO - Should this use normalized name like the linux guys?
    name in Windows <<= name,
    packageName in Windows <<= normalizedName,
    // Defaults so that our simplified building works
    candleOptions := Seq("-ext", "WixUtilExtension"),
    lightOptions := Seq("-ext", "WixUIExtension",
      "-ext", "WixUtilExtension",
      "-cultures:en-us"),
    wixProductId := WixHelper.makeGUID,
    wixProductUpgradeId := WixHelper.makeGUID,
    maintainer in Windows <<= maintainer,
    packageSummary in Windows <<= packageSummary,
    packageDescription in Windows <<= packageDescription,
    wixProductLicense <<= (sourceDirectory in Windows) map { dir =>
      // TODO - document this default.
      val default = dir / "License.rtf"
      if (default.exists) Some(default)
      else None
    },
    wixPackageInfo <<= (
      wixProductId,
      wixProductUpgradeId,
      version in Windows,
      maintainer in Windows,
      packageSummary in Windows,
      packageDescription in Windows) apply { (id, uid, version, mtr, title, desc) =>
        WindowsProductInfo(
          id = id,
          title = title,
          version = version,
          maintainer = mtr,
          description = desc,
          upgradeId = uid,
          comments = "TODO - we need comments." // TODO - allow comments
        )
      },
    wixFeatures := Seq.empty,
    wixProductConfig <<= (name in Windows, wixPackageInfo, wixFeatures, wixProductLicense) map { (name, product, features, license) =>
      WixHelper.makeWixProductConfig(name, product, features, license)
    },
    wixConfig <<= (name in Windows, wixPackageInfo, wixProductConfig) map { (name, product, nested) =>
      WixHelper.makeWixConfig(name, product, nested)
    },
    wixConfig in Windows <<= wixConfig,
    wixProductConfig in Windows <<= wixProductConfig,
    wixFile <<= (wixConfig in Windows, name in Windows, target in Windows) map { (c, n, t) =>
      val f = t / (n + ".wxs")
      IO.write(f, c.toString)
      f
    }
  ) ++ inConfig(Windows)(Seq(
      // Disable windows generation by default.
      mappings := Seq.empty,
      mappings in packageBin <<= mappings,
      packageMsi <<= (mappings in packageBin, wixFile, name, target, candleOptions, lightOptions, streams) map { (m, f, n, t, co, lo, s) =>
        {
          val msi = t / (n + ".msi")
          // First we have to move everything (including the wix file) to our target directory.
          val wix = t / (n + ".wix")
          if (f.getAbsolutePath != wix.getAbsolutePath) IO.copyFile(f, wix)
          IO.copy(for ((f, to) <- m) yield (f, t / to))
          // Now compile WIX
          val wixdir = Option(System.getenv("WIX")) getOrElse sys.error("WIX environment not found.  Please ensure WIX is installed on this computer.")
          val candleCmd = Seq(wixdir + "\\bin\\candle.exe", wix.getAbsolutePath) ++ co
          s.log.debug(candleCmd mkString " ")
          Process(candleCmd, Some(t)) ! s.log match {
            case 0 => ()
            case x => sys.error("Unable to run WIX compilation to wixobj...")
          }
          // Now create MSI
          val wixobj = t / (n + ".wixobj")
          val lightCmd = Seq(wixdir + "\\bin\\light.exe", wixobj.getAbsolutePath) ++ lo
          s.log.debug(lightCmd mkString " ")
          Process(lightCmd, Some(t)) ! s.log match {
            case 0 => ()
            case x => sys.error("Unable to run build msi...")
          }
          msi
        }
      },
      generateWinswFiles <<= (getWinswExe, createWinswXml, name) map { (exe, xml, n) =>

        Seq(
          (exe, "bin/", n + "_service.exe"),
          (xml, "bin/", n + "_service.xml")
        )
      },
      //This is an alternative strategy to package ZIP file
      packageZip <<= (generateWinswFiles, packageBin in Universal, target in Windows, streams) map { (winsw, zipUniversal, target, s) =>

        // Copy Universal zip to Windows target
        val zipWindows = target / zipUniversal.getName // new windows service zip
        val result = IO.copyFile(zipUniversal, zipWindows);

        //Get zip name that will be the folder name
        // all zipped filed are in a parent folder that is the name of the zip
        val zipName = zipWindows.getName
        val rootFolderName = if (zipName endsWith ".zip") zipName dropRight 4 else zipName

        //Create Seq of files to add with the path
        //We append rootFolderName to existing path
        // In universal zip a parent folder is created with the name of zip file so we need to take this in account
        val filesToAdd = winsw collect {
          case (file: File, path: String, name: String) => (file, rootFolderName + "/" + path, name)
        }

        //Finally add files to zip in Windows target
        ZipHelper.addFilesToExistingZip(zipWindows, filesToAdd)

        zipWindows
      },
      packageBin <<= packageMsi //Default strategy for `packageBin` is the generation of the MSI
    ))
}
package com.typesafe.sbt
package packager
package windows

import sbt._

trait WindowsKeys {

  val wixProductId = SettingKey[String]("wix-product-id", "The uuid of the windows package.")
  val wixProductUpgradeId = SettingKey[String]("wix-product-upgrade-id", "The uuid associated with upgrades for this package.")
  val wixPackageInfo = SettingKey[WindowsProductInfo]("wix-package-info", "The configuration for this package.")
  val wixProductLicense = TaskKey[Option[File]]("wix-product-license", "The RTF file to display with licensing.")
  val wixFeatures = TaskKey[Seq[WindowsFeature]]("wix-features", "Configuration of the windows installable features for this package.")
  val wixProductConfig = TaskKey[xml.Node]("wix-product-xml", "The WIX XML configuration for a product (nested in Wix/Product elements).")
  val wixConfig = TaskKey[xml.Node]("wix-xml", "The WIX XML configuration for this package.")
  val wixFile = TaskKey[File]("wix-file", "The WIX XML file to package with.")
  /**
   * `packageMsi` is not deprecated anymore but is the default strategy for `windows:packageBin`.
   */
  val packageMsi = TaskKey[File]("package-msi", "Default strategy for `windows:packageBin`. Creates a windows MSI file containing everything for the installation. Do NOT include Winsw files (yet).")
  val packageZip = TaskKey[File]("package-zip", "Alternative strategy for `packageBin`. Generate an Universal ZIP but add Winsw files in `/bin`. You can call this task directly but prefer to change default strategy of `windows:packageBin` by adding `packageBin in Windows <<= packageZip in Windows` in your `build.sbt`. If you want an MSI then see simply use `windows:packageBin` but MSI won't include Winsw files.")
  //This is experimental
  val generateWinswFiles = TaskKey[Seq[(java.io.File, String, String)]]("generateWinswFiles", "Creates Winsw files for Windows Services (First file is winsw exe and the second is the xml config). java.io.File : the file,  String : relative path, String : name of the target.")
  val getWinswExe = TaskKey[File]("getWinswExe", "Retrieve Winsw Exe and place it into windows/tmp/bin")
  val createWinswXml = TaskKey[File]("createWinswXml", "Create XML Service description used by Winsw exe.")
  //My first idea was to avoid all the settings service in windows.Keys but I was not able to use `SettingKey[WindowsServiceOptions]` from my build.sbt
  //Wrapper for all service* settings bellow
  val windowsServiceOptions = SettingKey[WindowsServiceOptions]("windowsServiceOptions", "Windows Service Options.")

  val serviceId = SettingKey[Option[String]]("serviceId", "Windows Service Unique Identifier")
  val serviceName = SettingKey[Option[String]]("serviceName", "Windows Service Display Name")
  val serviceDescription = SettingKey[Option[String]]("serviceDescription", "Windows Service Description")
  val serviceNoPid = SettingKey[Boolean]("serviceNoPid", "Indicate if we add -Dpidfile.path and set it to `NUL` to avoid to generate PID file. Used by Play application. To disable it add `serviceNoPid : None` in you build.sbt.")
  val serviceLogmode = SettingKey[Option[String]]("serviceLogmode", "Windows Service Default Log Mode. Default value `rotate` .To disable it add `serviceDefaultLogmode : None` in you build.sbt. Then see `serviceExtraXml`.")
  val serviceOnfailure = SettingKey[Option[String]]("serviceOnfailure", "Windows Service Default On Failure Action. Default value `rotate` .To disable it add `serviceDefaultLogmode : None` in you build.sbt. Then see `serviceExtraXml`.")
  val serviceExtraXml = SettingKey[Option[xml.Node]]("serviceExtraXml", "Extra XML to append in `<service>` tag after all other settings.")
  val serviceJavaArgs = SettingKey[Option[String]]("serviceJavaArgs", "Java Args `javaArgs` append at the end")

  val candleOptions = SettingKey[Seq[String]]("candle-options", "Options to pass to the candle.exe program.")
  val lightOptions = SettingKey[Seq[String]]("light-options", "Options to pass to the light.exe program.")
}

/*
 * Represent Windows Service Options
 *
 * Wrapping Windows Service Options in this case class.
 * Following what is in `RpmMetadata` in `RpmMetadata.scala`.
 * It's a looks a duplication with SettingKey[_] but it makes easier to send these options to methods.
 */
case class WindowsServiceOptions(
  id: Option[String],
  name: Option[String],
  description: Option[String],
  noPid: Boolean,
  logmode: Option[String],
  onfailure: Option[String],
  extraXml: Option[xml.Node],
  javaArgs: Option[String]) {
}

object Keys extends WindowsKeys {
  def version = sbt.Keys.version
  def target = sbt.Keys.target
  def mappings = sbt.Keys.mappings
  def name = sbt.Keys.name
  def packageName = packager.Keys.packageName
  def executableScriptName = packager.Keys.executableScriptName
  def streams = sbt.Keys.streams
  def sourceDirectory = sbt.Keys.sourceDirectory
  def packageBin = sbt.Keys.packageBin
  def dist = packageBin //Alias for people used to use `dist` (Play users). They must know that they have to do `windows:dist` to get Winsw files.
  def maintainer = packager.Keys.maintainer
  def packageSummary = packager.Keys.packageSummary
  def packageDescription = packager.Keys.packageDescription
}
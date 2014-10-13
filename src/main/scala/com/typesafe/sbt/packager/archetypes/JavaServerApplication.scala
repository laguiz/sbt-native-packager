package com.typesafe.sbt
package packager
package archetypes

import Keys._
import sbt._
import sbt.Keys.{ target, mainClass, sourceDirectory, streams, normalizedName }
import SbtNativePackager._
import com.typesafe.sbt.packager.linux.{ LinuxFileMetaData, LinuxPackageMapping, LinuxSymlink, LinuxPlugin }
import com.typesafe.sbt.packager.debian.DebianPlugin
import com.typesafe.sbt.packager.rpm.RpmPlugin
import com.typesafe.sbt.packager.windows.WindowsServiceOptions
import sbt.Keys.mainClass
import sbt.std.TaskStreams

import scala.xml.PrettyPrinter

/**
 * This class contains the default settings for creating and deploying an archetypical Java application.
 *  A Java application archetype is defined as a project that has a main method and is run by placing
 *  all of its JAR files on the classpath and calling that main method.
 *
 *  This doesn't create the best of distributions, but it can simplify the distribution of code.
 *
 *  **NOTE:  EXPERIMENTAL**   This currently only supports debian upstart scripts.
 */
object JavaServerAppPackaging {
  import ServerLoader._
  import LinuxPlugin.Users

  def settings: Seq[Setting[_]] = JavaAppPackaging.settings ++ linuxSettings ++ debianSettings ++ rpmSettings ++ windowsSettings
  protected def etcDefaultTemplateSource: java.net.URL = getClass.getResource("etc-default-template")

  private[this] def makeStartScriptReplacements(
    requiredStartFacilities: String,
    requiredStopFacilities: String,
    startRunlevels: String,
    stopRunlevels: String,
    loader: ServerLoader): Seq[(String, String)] = {
    loader match {
      case SystemV =>
        Seq("start_runlevels" -> startRunlevels,
          "stop_runlevels" -> stopRunlevels,
          "start_facilities" -> requiredStartFacilities,
          "stop_facilities" -> requiredStopFacilities)
      case Upstart =>
        Seq("start_runlevels" -> startRunlevels,
          "stop_runlevels" -> stopRunlevels,
          "start_facilities" -> requiredStartFacilities,
          "stop_facilities" -> requiredStopFacilities)
      case Systemd =>
        Seq("start_facilities" -> requiredStartFacilities)
    }
  }

  private[this] def defaultFacilities(loader: ServerLoader): String = {
    loader match {
      case SystemV => "$remote_fs $syslog"
      case Upstart => "[networking]"
      case Systemd => "network.target"
    }
  }

  private[this] def defaultStartRunlevels(loader: ServerLoader): String = {
    loader match {
      case SystemV => "2 3 4 5"
      case Upstart => "[2345]"
      case Systemd => ""
    }
  }

  private[this] def defaultStopRunlevels(loader: ServerLoader): String = {
    loader match {
      case SystemV => "0 1 6"
      case Upstart => "[016]"
      case Systemd => ""
    }
  }

  private[this] def getStartScriptLocation(loader: ServerLoader): String = {
    loader match {
      case Upstart => "/etc/init/"
      case SystemV => "/etc/init.d/"
      case Systemd => "/usr/lib/systemd/system/"
    }
  }

  /**
   * Experimental Windows settings
   *
   */
  def windowsSettings: Seq[Setting[_]] = {

    Seq(
      serviceId := None,
      serviceName := None,
      serviceDescription := None,
      serviceNoPid := true,
      serviceLogmode := Some("rotate"),
      serviceOnfailure := Some("restart"),
      serviceExtraXml := None,
      serviceJavaArgs := None,
      windowsServiceOptions <<= (serviceId, serviceName, serviceDescription, serviceNoPid, serviceLogmode, serviceOnfailure, serviceExtraXml, serviceJavaArgs) apply WindowsServiceOptions,
      getWinswExe <<= (target in Windows, streams) map doGetWinswExe,
      createWinswXml <<= (normalizedName, windowsServiceOptions, target in Windows, scriptClasspath, Keys.mainClass in Compile, streams) map doCreateWinswXml)
  }

  /**
   * general settings which apply to all linux server archetypes
   *
   * - script replacements
   * - logging directory
   * - config directory
   */
  def linuxSettings: Seq[Setting[_]] = Seq(
    // === logging directory mapping ===
    linuxPackageMappings <+= (packageName in Linux, defaultLinuxLogsLocation, daemonUser in Linux, daemonGroup in Linux) map {
      (name, logsDir, user, group) => packageTemplateMapping(logsDir + "/" + name)() withUser user withGroup group withPerms "755"
    },
    linuxPackageSymlinks <+= (packageName in Linux, defaultLinuxInstallLocation, defaultLinuxLogsLocation) map {
      (name, install, logsDir) => LinuxSymlink(install + "/" + name + "/logs", logsDir + "/" + name)
    },
    // === etc config mapping ===
    bashScriptConfigLocation <<= (packageName in Linux) map (name => Some("/etc/default/" + name)),
    linuxEtcDefaultTemplate <<= sourceDirectory map { dir =>
      val overrideScript = dir / "templates" / "etc-default"
      if (overrideScript.exists) overrideScript.toURI.toURL
      else etcDefaultTemplateSource
    },
    makeEtcDefault <<= (packageName in Linux, target in Universal, linuxEtcDefaultTemplate, linuxScriptReplacements)
      map makeEtcDefaultScript,
    linuxPackageMappings <++= (makeEtcDefault, packageName in Linux) map { (conf, name) =>
      conf.map(c => LinuxPackageMapping(Seq(c -> ("/etc/default/" + name)),
        LinuxFileMetaData(Users.Root, Users.Root)).withConfig()).toSeq
    },

    // === /var/run/app pid folder ===
    linuxPackageMappings <+= (packageName in Linux, daemonUser in Linux, daemonGroup in Linux) map { (name, user, group) =>
      packageTemplateMapping("/var/run/" + name)() withUser user withGroup group withPerms "755"
    })

  def debianSettings: Seq[Setting[_]] = {
    import DebianPlugin.Names.{ Preinst, Postinst, Prerm, Postrm }
    inConfig(Debian)(Seq(
      serverLoading := Upstart,
      startRunlevels <<= (serverLoading) apply defaultStartRunlevels,
      stopRunlevels <<= (serverLoading) apply defaultStopRunlevels,
      requiredStartFacilities <<= (serverLoading) apply defaultFacilities,
      requiredStopFacilities <<= (serverLoading) apply defaultFacilities,
      linuxJavaAppStartScriptBuilder := JavaAppStartScript.Debian,
      // === Startscript creation ===
      linuxScriptReplacements <++= (requiredStartFacilities, requiredStopFacilities, startRunlevels, stopRunlevels, serverLoading) apply
        makeStartScriptReplacements,
      linuxStartScriptTemplate <<= (serverLoading, sourceDirectory, linuxJavaAppStartScriptBuilder) map {
        (loader, dir, builder) => builder.defaultStartScriptTemplate(loader, dir / "templates" / "start")
      },
      defaultLinuxStartScriptLocation <<= (serverLoading) apply getStartScriptLocation,
      linuxMakeStartScript <<= (target in Universal, serverLoading, linuxScriptReplacements, linuxStartScriptTemplate, linuxJavaAppStartScriptBuilder)
        map { (tmpDir, loader, replacements, template, builder) =>
          makeMaintainerScript(builder.startScript, Some(template))(tmpDir, loader, replacements, builder)
        },
      linuxPackageMappings <++= (packageName, linuxMakeStartScript, serverLoading, defaultLinuxStartScriptLocation) map startScriptMapping
    )) ++ Seq(
      // === Maintainer scripts === 
      debianMakePreinstScript <<= (target in Universal, serverLoading in Debian, linuxScriptReplacements, linuxJavaAppStartScriptBuilder in Debian) map makeMaintainerScript(Preinst),
      debianMakePostinstScript <<= (target in Universal, serverLoading in Debian, linuxScriptReplacements, linuxJavaAppStartScriptBuilder in Debian) map makeMaintainerScript(Postinst),
      debianMakePrermScript <<= (target in Universal, serverLoading in Debian, linuxScriptReplacements, linuxJavaAppStartScriptBuilder in Debian) map makeMaintainerScript(Prerm),
      debianMakePostrmScript <<= (target in Universal, serverLoading in Debian, linuxScriptReplacements, linuxJavaAppStartScriptBuilder in Debian) map makeMaintainerScript(Postrm))
  }

  def rpmSettings: Seq[Setting[_]] = {
    import RpmPlugin.Names.{ Pre, Post, Preun, Postun }
    inConfig(Rpm)(Seq(
      serverLoading := SystemV,
      startRunlevels <<= (serverLoading) apply defaultStartRunlevels,
      stopRunlevels in Rpm <<= (serverLoading) apply defaultStopRunlevels,
      requiredStartFacilities in Rpm <<= (serverLoading) apply defaultFacilities,
      requiredStopFacilities in Rpm <<= (serverLoading) apply defaultFacilities,
      linuxJavaAppStartScriptBuilder := JavaAppStartScript.Rpm,
      linuxScriptReplacements <++= (requiredStartFacilities, requiredStopFacilities, startRunlevels, stopRunlevels, serverLoading) apply
        makeStartScriptReplacements
    )) ++ Seq(
      // === Startscript creation ===
      linuxStartScriptTemplate in Rpm <<= (serverLoading in Rpm, sourceDirectory, linuxJavaAppStartScriptBuilder in Rpm) map {
        (loader, dir, builder) =>
          builder.defaultStartScriptTemplate(loader, dir / "templates" / "start")
      },
      linuxMakeStartScript in Rpm <<= (target in Universal, serverLoading in Rpm, linuxScriptReplacements in Rpm, linuxStartScriptTemplate in Rpm, linuxJavaAppStartScriptBuilder in Rpm)
        map { (tmpDir, loader, replacements, template, builder) =>
          makeMaintainerScript(builder.startScript, Some(template))(tmpDir, loader, replacements, builder)
        },
      defaultLinuxStartScriptLocation in Rpm <<= (serverLoading in Rpm) apply getStartScriptLocation,
      linuxPackageMappings in Rpm <++= (packageName in Rpm, linuxMakeStartScript in Rpm, serverLoading in Rpm, defaultLinuxStartScriptLocation in Rpm) map startScriptMapping,

      // == Maintainer scripts ===
      // TODO this is very basic - align debian and rpm plugin
      rpmPre <<= (rpmScriptsDirectory, rpmPre, linuxScriptReplacements, serverLoading in Rpm, linuxJavaAppStartScriptBuilder in Rpm) apply {
        (dir, pre, replacements, loader, builder) =>
          Some(pre.map(_ + "\n").getOrElse("") + rpmScriptletContent(dir, Pre, loader, replacements, builder))
      },
      rpmPost <<= (rpmScriptsDirectory, rpmPost, linuxScriptReplacements, serverLoading in Rpm, linuxJavaAppStartScriptBuilder in Rpm) apply {
        (dir, post, replacements, loader, builder) =>
          Some(post.map(_ + "\n").getOrElse("") + rpmScriptletContent(dir, Post, loader, replacements, builder))
      },
      rpmPostun <<= (rpmScriptsDirectory, rpmPostun, linuxScriptReplacements, serverLoading in Rpm, linuxJavaAppStartScriptBuilder in Rpm) apply {
        (dir, postun, replacements, loader, builder) =>
          Some(postun.map(_ + "\n").getOrElse("") + rpmScriptletContent(dir, Postun, loader, replacements, builder))
      },
      rpmPreun <<= (rpmScriptsDirectory, rpmPreun, linuxScriptReplacements, serverLoading in Rpm, linuxJavaAppStartScriptBuilder in Rpm) apply {
        (dir, preun, replacements, loader, builder) =>
          Some(preun.map(_ + "\n").getOrElse("") + rpmScriptletContent(dir, Preun, loader, replacements, builder))
      })
  }

  /* ==========================================  */
  /* ============ Helper Methods ==============  */
  /* ==========================================  */

  protected def startScriptMapping(name: String, script: Option[File], loader: ServerLoader, scriptDir: String): Seq[LinuxPackageMapping] = {
    val (path, permissions) = loader match {
      case Upstart => ("/etc/init/" + name + ".conf", "0644")
      case SystemV => ("/etc/init.d/" + name, "0755")
      case Systemd => ("/usr/lib/systemd/system/" + name + ".service", "0644")
    }
    for {
      s <- script.toSeq
    } yield LinuxPackageMapping(Seq(s -> path), LinuxFileMetaData(Users.Root, Users.Root, permissions, "true"))
  }

  protected def makeMaintainerScript(scriptName: String, template: Option[URL] = None)(
    tmpDir: File, loader: ServerLoader, replacements: Seq[(String, String)], builder: JavaAppStartScriptBuilder): Option[File] = {
    builder.generateTemplate(scriptName, loader, replacements, template) map { scriptBits =>
      val script = tmpDir / "tmp" / "bin" / (builder.name + scriptName)
      IO.write(script, scriptBits)
      script
    }
  }

  protected def makeEtcDefaultScript(name: String, tmpDir: File, source: java.net.URL, replacements: Seq[(String, String)]): Option[File] = {
    val scriptBits = TemplateWriter.generateScript(source, replacements)
    val script = tmpDir / "tmp" / "etc" / "default" / name
    IO.write(script, scriptBits)
    Some(script)
  }

  protected def rpmScriptletContent(dir: File, script: String,
    loader: ServerLoader, replacements: Seq[(String, String)], builder: JavaAppStartScriptBuilder): String = {
    val file = (dir / script)
    val template = if (file exists) Some(file.toURI.toURL) else None
    builder.generateTemplate(script, loader, replacements, template).getOrElse(sys.error("Could generate content for script: " + script))
  }

  /* ==========================================  */
  /* ===== Windows Winsw helpers Methods ======  */
  /* ==========================================  */

  /**
   * Retrieve winsw.exe and place it into windows/tmp folder.
   *
   * So far, we only get the exe from jenkins repository (version is fixed to the current latest version 1.16).
   * Current URL is : http://repo.jenkins-ci.org/releases/com/sun/winsw/winsw/1.16/winsw-1.16-bin.exe
   *
   * Winsw license : https://github.com/kohsuke/winsw/blob/master/LICENSE.txt
   * See LICENSE_WINSW.md
   *
   * Original name for this task is `downloadWinsw` but `download` sounds too restrictive. I (Maxence) prefer `get`.
   *
   *
   * TODO
   * We maybe want to provide mode options to get the exe
   * 1. Give alternative URL to download
   * 2. Embedded a tested exe into sbt-native-packager
   * 3. Try to find the exe in the project (for example look in /conf or something similar)
   * 4. Give custom path
   */
  protected def doGetWinswExe(windowsDir: File, streams: TaskStreams[_]): File = {

    //TODO laguiz we have to also package the license somewhere (license is here : LICENSE_WINSW.md)
    // Should we also package this file in the generated zip?
    //TODO laguiz give the option to provide the exe from (see scaladoc above for more details on these options)

    streams.log.debug("Getting Winsw Exe...")
    //Default URL where we fetch the bin (this could be the out-of-the-box option)
    val uri = s"http://repo.jenkins-ci.org/releases/com/sun/winsw/winsw/1.16/winsw-1.16-bin.exe"
    val fileToDownload = url(uri);
    val binFileName = "winsw.exe";
    val exeFile = windowsDir / "tmp" / "bin" / binFileName;
    IO.download(fileToDownload, exeFile)
    streams.log.debug("Winsw exe fetched here : " + exeFile)
    exeFile
  }

  /**
   * Create the Winsw XML file needed by the exe file
   *
   * Java command structure :
   * `java %JAVA_OPTS% %MY_APP_NAME_OPTS% [noPid] -cp [appClasspath] [mainClass] [javaArgs]`
   *
   *
   *
   */
  protected def doCreateWinswXml(name: String, windowsServiceOptions: WindowsServiceOptions, windowsDir: File, appClasspath: Seq[String] = Seq("*"), mainClass: Option[String], streams: TaskStreams[_]): File = {

    streams.log.debug("Creating Winsw Xml file...")

    val options = windowsServiceOptions;
    /*
     * Variables construction
     */
    val xmlFileName = "winsw.xml";
    val xmlFile = windowsDir / "tmp" / "bin" / xmlFileName;
    val noPid = if (options.noPid) { Some("""-Dpidfile.path="NUL"""") } else { None }
    val doubleQuote = """"""";
    val clazz = mainClass.getOrElse("Main")

    //Take all libraries in classpath and create the final Strings with `;` and relative path to `..\lib\`
    val relativeAppClasspath =
      appClasspath map {
        (s) => """..\lib\""" + s;
      } mkString (";");

    //If not specified serviceId is equals to the normalizedName
    //QUESTION : how to avoid `name => name`? I was expecting something like `normalizedName apply _` but it does not work
    val serviceId = options.id.getOrElse(name)
    /*
     * Create Sequence of optional arguments
     */

    //Is there a cleaner way to write this? Or a better idea?
    val arguments: Seq[Option[String]] = Seq() :+
      Some("%JAVA_OPTS%") :+
      Some("%" + JavaAppBatScript.makeEnvFriendlyName(name) + "_OPTS%") :+
      noPid :+ //Windows Service will manage the java process. This will tell Play to NOT generate the PID file (no issue if JVM crash for instance because the Windows Service monitor the process)
      Some("-cp") :+
      Some(doubleQuote + relativeAppClasspath + doubleQuote) :+
      Some(clazz) :+
      options.javaArgs

    streams.log.debug(scala.xml.Unparsed(buildArgumentsString(arguments)).toString)

    //Basic arguments support
    //Should we use a template maybe? Not sure because here it's pretty easy
    val xml = {
      <service>
        <id>{ options.id.getOrElse(serviceId) }</id>
        <name>{ options.name.getOrElse(serviceId) }</name>
        <description>{ options.description.getOrElse(serviceId) }</description>
        <executable>java</executable>
        <arguments>{ scala.xml.Unparsed(buildArgumentsString(arguments)) }</arguments>
        { if (options.logmode.isDefined) <logmode>{ options.logmode.get }</logmode> }
        { if (options.onfailure.isDefined) <onfailure action={ options.onfailure.get }/> }
        { if (options.extraXml.isDefined) options.extraXml.get }
      </service>
    }

    IO.write(xmlFile, xml.toString);

    streams.log.debug("Winsw XML created here : " + xmlFile)

    xmlFile

  }

  /*
   * Following methods are technical helpers used in doCreateWinswXml()
   * Any better way ?
   */

  private def buildArgumentsString(arguments: Seq[Option[String]]): String = arguments.flatMap(s => s).filter(s => s.nonEmpty).mkString(" ")

}

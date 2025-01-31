package io.joern.ghidra2cpg

import ghidra.GhidraJarApplicationLayout
import ghidra.app.decompiler.{DecompInterface, DecompileOptions}
import ghidra.app.plugin.core.analysis.AutoAnalysisManager
import ghidra.app.util.importer.{AutoImporter, MessageLog}
import ghidra.framework.model.{Project, ProjectLocator}
import ghidra.framework.project.{DefaultProject, DefaultProjectManager}
import ghidra.framework.protocol.ghidra.{GhidraURLConnection, Handler}
import ghidra.framework.{Application, HeadlessGhidraApplicationConfiguration}
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.listing.Program
import ghidra.program.util.GhidraProgramUtilities
import ghidra.util.SystemUtilities
import ghidra.util.exception.InvalidInputException
import ghidra.util.task.TaskMonitor
import io.joern.ghidra2cpg.passes.{FunctionPass, MetaDataPass, NamespacePass, TypesPass}
import io.joern.ghidra2cpg.processors.X86
import io.shiftleft.dataflowengineoss.passes.reachingdef.ReachingDefPass
import io.shiftleft.passes.KeyPoolCreator
import io.shiftleft.semanticcpg.passes.FileCreationPass
import io.shiftleft.semanticcpg.passes.containsedges.ContainsEdgePass
import io.shiftleft.semanticcpg.passes.languagespecific.fuzzyc.{
  MethodStubCreator,
  TypeDeclStubCreator
}
import io.shiftleft.semanticcpg.passes.linking.calllinker.StaticCallLinker
import io.shiftleft.semanticcpg.passes.linking.linker.Linker
import io.shiftleft.semanticcpg.passes.linking.memberaccesslinker.MemberAccessLinker
import io.shiftleft.semanticcpg.passes.methoddecorations.MethodDecoratorPass
import io.shiftleft.semanticcpg.passes.methodexternaldecorator.MethodExternalDecoratorPass
import io.shiftleft.semanticcpg.passes.namespacecreator.NamespaceCreator
import io.shiftleft.x2cpg.X2Cpg
import org.apache.commons.io.FileUtils
import utilities.util.FileUtilities

import java.io.File
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object Types {

  // Types will be added to the CPG as soon as everything
  // else is done
  val types: mutable.SortedSet[String] = scala.collection.mutable.SortedSet[String]()
  def registerType(typeName: String): String = {
    types += typeName
    typeName
  }
}
class Ghidra2Cpg(
    inputFile: String,
    outputFile: Option[String]
) {

  val tempWorkingDir: File = Files.createTempDirectory("ghidra2cpg").toFile
  // tempWorkingDir.deleteOnExit() is not reliable,
  // adding a shutdown hook seems to work https://stackoverflow.com/posts/35212952/revisions
  Runtime.getRuntime.addShutdownHook(new Thread(() => FileUtils.deleteQuietly(tempWorkingDir)))

  def createCpg(): Unit = {
    // We need this for the URL handler
    Handler.registerHandler()

    var projectManager: Option[HeadlessGhidraProjectManager] =
      None: Option[HeadlessGhidraProjectManager]

    var project: Option[Project] = None
    // Initialize application (if necessary and only once)
    if (!Application.isInitialized) {
      val configuration = new HeadlessGhidraApplicationConfiguration
      configuration.setInitializeLogging(false)
      configuration.setApplicationLogFile(new File("/dev/null"))
      configuration.setScriptLogFile(new File("/dev/null"))
      configuration.setApplicationLogFile(new File("/dev/null"))
      Application.initializeApplication(new GhidraJarApplicationLayout, configuration)
    }
    if (!new File(inputFile).isDirectory && !new File(inputFile).isFile)
      throw new InvalidInputException(
        s"$inputFile is not a valid directory or file."
      )

    val locator          = new ProjectLocator(tempWorkingDir.getAbsolutePath, CommandLineConfig.projectName)
    var program: Program = null
    try {
      projectManager = Some(new HeadlessGhidraProjectManager)
      project = Some(projectManager.get.createProject(locator, null, false))
      program = AutoImporter.importByUsingBestGuess(
        new File(inputFile),
        null,
        this,
        new MessageLog,
        TaskMonitor.DUMMY
      )

      analyzeProgram(Paths.get(inputFile).toFile.getAbsolutePath, program)
    } catch {
      case e: Throwable =>
        e.printStackTrace()
    } finally {
      if (program != null) {
        AutoAnalysisManager.getAnalysisManager(program).dispose()
        program.release(this)
        program = null
      }
      project.get.close()
      // Used to have this in a config but we delete the directory anyway
      // if (!config.runScriptsNoImport && config.deleteProject)
      FileUtilities.deleteDir(locator.getProjectDir)
      locator.getMarkerFile.delete

    }
  }
  private def analyzeProgram(fileAbsolutePath: String, program: Program): Unit = {
    val autoAnalysisManager: AutoAnalysisManager = AutoAnalysisManager.getAnalysisManager(program)
    val transactionId: Int                       = program.startTransaction("Analysis")
    try {
      autoAnalysisManager.initializeOptions()
      autoAnalysisManager.reAnalyzeAll(null)
      autoAnalysisManager.startAnalysis(TaskMonitor.DUMMY)
      GhidraProgramUtilities.setAnalyzedFlag(program, true)
    } catch {
      case e: Throwable =>
        e.printStackTrace()
    } finally {
      program.endTransaction(transactionId, true)
    }
    try {
      handleProgram(program, fileAbsolutePath)
    } catch {
      case e: Throwable =>
        e.printStackTrace()
    }
  }

  private class HeadlessProjectConnection(
      projectManager: HeadlessGhidraProjectManager,
      connection: GhidraURLConnection
  ) extends DefaultProject(projectManager, connection) {}

  private class HeadlessGhidraProjectManager extends DefaultProjectManager {}

  def handleProgram(currentProgram: Program, fileAbsolutePath: String): Unit = {

    val flatProgramAPI: FlatProgramAPI = new FlatProgramAPI(currentProgram)
    val decompilerInterface            = new DecompInterface()
    decompilerInterface.toggleCCode(false)
    decompilerInterface.toggleSyntaxTree(false)
    decompilerInterface.toggleJumpLoads(false)
    decompilerInterface.toggleParamMeasures(true)
    decompilerInterface.setSimplificationStyle("decompile")

    val opts = new DecompileOptions()

    opts.grabFromProgram(currentProgram)
    decompilerInterface.setOptions(opts)

    println(s"""[ + ] Starting CPG generation""")
    if (!decompilerInterface.openProgram(currentProgram)) {
      println("Decompiler error: %s\n", decompilerInterface.getLastMessage)
    }
    // Functions
    val listing          = currentProgram.getListing
    val functionIterator = listing.getFunctions(true)
    val functions        = functionIterator.iterator.asScala.toList

    // We touch every function twice, regular ASM and PCode
    // Also we have + 2 for MetaDataPass and Namespacepass
    val numOfKeypools = functions.size * 2 + 2
    val keyPools      = KeyPoolCreator.obtain(numOfKeypools).iterator

    // Actual CPG construction
    val cpg = X2Cpg.newEmptyCpg(outputFile)

    new MetaDataPass(fileAbsolutePath, cpg, keyPools.next()).createAndApply()
    new NamespacePass(cpg, fileAbsolutePath, keyPools.next()).createAndApply()

    val processor = currentProgram.getLanguage.getLanguageDescription.getProcessor.toString match {
      case _ => new X86
    }
    functions.distinctBy(_.getName).foreach { function =>
      new FunctionPass(
        processor,
        currentProgram,
        fileAbsolutePath,
        functions,
        function,
        cpg,
        keyPools.next(),
        decompilerInterface,
        flatProgramAPI
      )
        .createAndApply()
    }

    new TypesPass(cpg).createAndApply()
    new TypeDeclStubCreator(cpg).createAndApply()
    new MethodStubCreator(cpg).createAndApply()
    new MethodDecoratorPass(cpg).createAndApply()
    new Linker(cpg).createAndApply()
    new FileCreationPass(cpg).createAndApply()
    new StaticCallLinker(cpg).createAndApply()
    new MemberAccessLinker(cpg).createAndApply()
    new MethodExternalDecoratorPass(cpg).createAndApply()
    new ContainsEdgePass(cpg).createAndApply()
    new NamespaceCreator(cpg).createAndApply()
    new ReachingDefPass(cpg).createAndApply()
    cpg.close()
  }
}

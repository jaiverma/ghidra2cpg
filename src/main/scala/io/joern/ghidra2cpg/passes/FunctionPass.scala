package io.joern.ghidra2cpg.passes

import ghidra.app.decompiler.DecompInterface
import ghidra.program.flatapi.FlatProgramAPI
import ghidra.program.model.address.GenericAddress
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.{
  CodeUnitFormat,
  CodeUnitFormatOptions,
  Function,
  Instruction,
  Program
}
import ghidra.program.model.scalar.Scalar
import ghidra.util.task.ConsoleTaskMonitor
import io.joern.ghidra2cpg._
import io.joern.ghidra2cpg.processors._
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{NewCall, NewCallBuilder, NewMethod}
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, NodeTypes, nodes}
import io.shiftleft.passes.{DiffGraph, IntervalKeyPool, ParallelCpgPass}
import io.shiftleft.proto.cpg.Cpg.DispatchTypes

import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

class FunctionPass(
    processor: Processor,
    currentProgram: Program,
    filename: String,
    functions: List[Function],
    function: Function,
    cpg: Cpg,
    keyPool: IntervalKeyPool,
    decompInterface: DecompInterface,
    flatProgramAPI: FlatProgramAPI
) extends ParallelCpgPass[String](
      cpg,
      keyPools = Some(keyPool.split(1))
    ) {

  implicit val diffGraph: DiffGraph.Builder = DiffGraph.newBuilder
  private var methodNode: Option[NewMethod] = None
  // we need it just once with default settings
  private val blockNode = nodes.NewBlock().code("").order(0)
  // needed by ghidra for decompiling reasons
  private val codeUnitFormat: CodeUnitFormat = new CodeUnitFormat(
    new CodeUnitFormatOptions(
      CodeUnitFormatOptions.ShowBlockName.NEVER,
      CodeUnitFormatOptions.ShowNamespace.NEVER,
      "",
      true,
      true,
      true,
      true,
      true,
      true,
      true
    )
  )

  override def partIterator: Iterator[String] = List("").iterator

  // Iterating over operands and add edges to call
  def handleArguments(
      instruction: Instruction,
      callNode: NewCall
  ): Unit = {
    if (instruction.getMnemonicString.contains("CALL")) {
      val mnemonicName = codeUnitFormat.getOperandRepresentationString(instruction, 0)
      val callee       = functions.find(xx => xx.getName().equals(mnemonicName))
      if (callee.nonEmpty)
        callee.head.getParameters.zipWithIndex
          .foreach { case (parameter, index) =>
            var checkedParameter = ""
            if (parameter.getRegister == null) checkedParameter = parameter.getName
            else checkedParameter = parameter.getRegister.getName

            val node = nodes
              .NewIdentifier()
              .code(checkedParameter)
              .name(checkedParameter) //parameter.getName)
              .order(index + 1)
              .argumentIndex(index + 1)
              .typeFullName(Types.registerType(parameter.getDataType.getName))
              .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
            diffGraph.addNode(node)
            diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
            diffGraph.addEdge(callNode, node, EdgeTypes.AST)
          }
    } else {
      for (index <- 0 until instruction.getNumOperands) {
        val opObjects = instruction.getOpObjects(index)
        if (opObjects.length > 1) {
          val argument = String.valueOf(
            instruction.getDefaultOperandRepresentation(index)
          )
          val node = nodes
            .NewIdentifier()
            .code(argument)
            .name(argument)
            .order(index + 1)
            .argumentIndex(index + 1)
            .typeFullName(Types.registerType(argument))
            .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
          diffGraph.addNode(node)
          diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
          diffGraph.addEdge(callNode, node, EdgeTypes.AST)
        } else
          for (opObject <- opObjects) { //
            val className = opObject.getClass.getSimpleName
            opObject.getClass.getSimpleName match {
              case "Register" =>
                val register = opObject.asInstanceOf[Register]
                val node = nodes
                  .NewIdentifier()
                  .code(register.getName)
                  .name(register.getName)
                  .order(index + 1)
                  .argumentIndex(index + 1)
                  .typeFullName(Types.registerType(register.getName))
                  .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
                diffGraph.addNode(node)
                diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
                diffGraph.addEdge(callNode, node, EdgeTypes.AST)
              case "Scalar" =>
                val scalar =
                  opObject.asInstanceOf[Scalar].toString(16, false, false, "", "")
                val node = nodes
                  .NewLiteral()
                  .code(scalar)
                  .order(index + 1)
                  .argumentIndex(index + 1)
                  .typeFullName(scalar)
                  .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
                diffGraph.addNode(node)
                diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
                diffGraph.addEdge(callNode, node, EdgeTypes.AST)
              case "GenericAddress" =>
                // TODO: try to resolve the address
                val genericAddress =
                  opObject.asInstanceOf[GenericAddress].toString()
                val node = nodes
                  .NewLiteral()
                  .code(genericAddress)
                  .order(index + 1)
                  .argumentIndex(index + 1)
                  .typeFullName(genericAddress)
                  .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
                diffGraph.addNode(node)
                diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
                diffGraph.addEdge(callNode, node, EdgeTypes.AST)
              case _ =>
                println(
                  s"""Unsupported argument: $opObject $className"""
                )
            }
          }
      }
    }
  }

  def handleParameters(): Unit = {
    if (function.isThunk) {
      function
        .getThunkedFunction(true)
        .getParameters
        .zipWithIndex
        .foreach { case (parameter, index) =>
          val node = nodes
            .NewMethodParameterIn()
            .code(parameter.getName)
            .name(parameter.getName)
            .order(index + 1)
            .typeFullName(Types.registerType(parameter.getDataType.getName))
            .lineNumber(Some(function.getEntryPoint.getOffsetAsBigInteger.intValue()))
          diffGraph.addNode(node)
          diffGraph.addEdge(methodNode.get, node, EdgeTypes.AST)
        }
    } else {
      decompInterface
        .decompileFunction(function, 60, new ConsoleTaskMonitor())
        .getHighFunction
        .getLocalSymbolMap
        .getSymbols
        .asScala
        .toSeq
        .filter(_.isParameter)
        .foreach { case parameter =>
          var checkedParameter = ""
          if (parameter.getStorage.getRegister == null) {
            checkedParameter = parameter.getName
          } else {
            checkedParameter = parameter.getStorage.getRegister.getName
          }
          val node = nodes
            .NewMethodParameterIn()
            .code(checkedParameter)
            .name(checkedParameter)
            .order(parameter.getCategoryIndex + 1)
            .typeFullName(Types.registerType(parameter.getDataType.getName))
            .lineNumber(Some(function.getEntryPoint.getOffsetAsBigInteger.intValue()))
          diffGraph.addNode(node)
          diffGraph.addEdge(methodNode.get, node, EdgeTypes.AST)
        }
    }
  }

  def handleLocals(): Unit = {
    function.getLocalVariables.foreach { local =>
      val localNode = nodes
        .NewLocal()
        .name(local.getName)
        .code(local.toString)
        .typeFullName(Types.registerType(local.getDataType.toString))
      val identifier = nodes
        .NewIdentifier()
        .code(local.getName)
        .name(local.getSymbol.getName)
        .typeFullName(local.getDataType.toString)

      diffGraph.addNode(localNode)
      diffGraph.addNode(identifier)
      diffGraph.addEdge(blockNode, localNode, EdgeTypes.AST)
      diffGraph.addEdge(blockNode, identifier, EdgeTypes.AST)
      diffGraph.addEdge(identifier, localNode, EdgeTypes.REF)
    }
  }

  def sanitizeMethodName(methodName: String): String = {
    methodName.split(">").lastOption.getOrElse(methodName).replace("[", "").replace("]", "")
  }

  def addCallNode(instruction: Instruction): NewCall = {
    val node: NewCallBuilder = nodes.NewCall()
    var code: String         = ""
    val mnemonicName =
      processor.getInstructions
        .getOrElse(instruction.getMnemonicString, "UNKNOWN") match {
        case "LEAVE" | "RET" =>
          code = "RET"
          "RET"
        case "CALL" =>
          val operandRepresentationString = sanitizeMethodName(
            codeUnitFormat.getOperandRepresentationString(instruction, 0)
          )
          code = operandRepresentationString
          operandRepresentationString
        case "UNKNOWN" =>
          code = instruction.toString
          "UNKNOWN"
        case operator =>
          code = instruction.toString
          operator
      }

    node
      .name(mnemonicName)
      .code(code)
      .order(0)
      .methodFullName(mnemonicName)
      .dispatchType(DispatchTypes.STATIC_DISPATCH.name())
      .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
      .build
  }

  def handleBody(): Unit = {
    val addressSet = function.getBody
    var instructions =
      currentProgram.getListing.getInstructions(addressSet, true).iterator().asScala.toList
    if (instructions.nonEmpty) {
      var prevInstructionNode = addCallNode(instructions.head)
      handleArguments(instructions.head, prevInstructionNode)
      diffGraph.addEdge(blockNode, prevInstructionNode, EdgeTypes.AST)
      diffGraph.addEdge(methodNode.get, prevInstructionNode, EdgeTypes.CFG)
      instructions.drop(1).foreach { instruction =>
        val instructionNode = addCallNode(instruction)
        diffGraph.addNode(instructionNode)
        handleArguments(instruction, instructionNode)
        diffGraph.addEdge(blockNode, instructionNode, EdgeTypes.AST)
        diffGraph.addEdge(prevInstructionNode, instructionNode, EdgeTypes.CFG)
        prevInstructionNode = instructionNode
      }
    }
  }
  def checkIfExternal(functionName: String): Boolean = {
    currentProgram.getFunctionManager.getExternalFunctions
      .iterator()
      .asScala
      .map(_.getName)
      .contains(functionName)
  }

  def createMethodNode(): Unit = {
    methodNode = Some(
      nodes
        .NewMethod()
        .code(function.getName)
        .name(function.getName)
        .fullName(function.getName)
        .isExternal(checkIfExternal(function.getName))
        .signature(function.getSignature(true).toString)
        .lineNumber(Some(function.getEntryPoint.getOffsetAsBigInteger.intValue()))
        .columnNumber(Some(-1))
        .lineNumberEnd(Some(function.getReturn.getMinAddress.getOffsetAsBigInteger.intValue()))
        .order(0)
        .filename(filename)
        .astParentType(NodeTypes.NAMESPACE_BLOCK)
        .astParentFullName(s"$filename:<global>")
    )

    diffGraph.addNode(methodNode.get)
    diffGraph.addNode(blockNode)
    diffGraph.addEdge(methodNode.get, blockNode, EdgeTypes.AST)

    // We need at least one of "NewMethodReturn"
    val methodReturn = nodes.NewMethodReturn().order(1)
    diffGraph.addNode(methodReturn)
    diffGraph.addEdge(methodNode.get, methodReturn, EdgeTypes.AST)
  }

  def handleLiterals(): Unit = {
    flatProgramAPI
      .findStrings(function.getBody, 4, 1, false, true)
      .forEach { y =>
        // get the actual value at the address
        val literal = flatProgramAPI
          .getBytes(y.getAddress, y.getLength)
          .map(_.toChar)
          .mkString("")

        val node = nodes
          .NewLiteral()
          .code(literal)
          .order(-1)
          .argumentIndex(-1)
          .typeFullName(literal)
        diffGraph.addNode(node)
        diffGraph.addEdge(blockNode, node, EdgeTypes.AST)
      }
  }
  override def runOnPart(part: String): Iterator[DiffGraph] = {
    createMethodNode()
    handleParameters()
    handleLocals()
    handleBody()
    handleLiterals()
    Iterator(diffGraph.build())
  }
}

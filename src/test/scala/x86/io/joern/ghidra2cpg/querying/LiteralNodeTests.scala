package x86.io.joern.ghidra2cpg.querying

import io.shiftleft.semanticcpg.language._

class LiteralNodeTests extends GhidraCodeToCpgSuite {

  override val code: String =
    """
      | int main() {
      |   int x;
      |   char y[] = "TEST";
      |   x = 100;
      |}
      |""".stripMargin

  "should contain exactly one literal node containing \"TEST\" with all mandatory fields set" in {
    cpg.method.name("main").literal.code("TEST").l match {
      case List(x) =>
        x.code shouldBe "TEST"
      case _ => fail()
    }
  }
  "should contain exactly one node with all mandatory fields set" in {
    // keep in mind: 0x64 = 100
    cpg.literal.code("64").l.size > 0
  }

  "should contain exactly one call with literal arguments" in {
    // keep in mind = 0x64
    cpg.call.name("<operator>.assignment").argument.code("64").l match {
      case List(x) =>
        x.code shouldBe "64"
      case _ => fail()
    }
  }
}

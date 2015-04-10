package io.coral.coralscript

case class TriggerCondition(id: Identifier, statements: ConditionBlock) extends Statement
case class TriggerAction(id: Identifier, statements: List[TriggerStatement]) extends Statement {
    println("trigger action, id: " + id.toString + ", statements: " + statements.toString)
}
case class TriggerDeclaration(action: Identifier, condition: Identifier) extends Statement
case class ConditionBlock(block: List[TriggerStatement])
case class TriggerStatement(s: Statement) extends Statement
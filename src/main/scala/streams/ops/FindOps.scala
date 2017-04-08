package scalaxy.streams

private[streams] trait FindOps
    extends ClosureStreamOps
    with Strippers
    with OptionSinks {
  val global: scala.reflect.api.Universe
  import global._

  object SomeFindOp extends StreamOpExtractor {
    override def unapply(tree: Tree) = tree match {
      case q"$target.find(${ Closure(closure) })" =>
        ExtractedStreamOp(target, FindOp(closure))

      case _ =>
        NoExtractedStreamOp
    }
  }
  case class FindOp(closure: Function)
      extends ClosureStreamOp {
    override def describe = Some("find")

    override def sinkOption = Some(OptionSink)

    override def canInterruptLoop = true

    override def canAlterSize = true

    override def isMapLike = false

    override def emit(
      input: StreamInput,
      outputNeeds: OutputNeeds,
      nextOps: OpsAndOutputNeeds
    ): StreamOutput =
      {
        import input.typed

        val (replacedStatements, outputVars) =
          transformationClosure.replaceClosureBody(
            input,
            outputNeeds + RootTuploidPath
          )

        var test = outputVars.alias.get

        var sub = emitSub(input.copy(outputSize = None), nextOps)
        sub.copy(body = List(q"""
        ..$replacedStatements;
        if ($test) {
          ..${sub.body};
          ${input.loopInterruptor.get.duplicate} = false;
        }
      """))
      }
  }
}

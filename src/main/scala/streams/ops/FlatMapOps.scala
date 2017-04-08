package scalaxy.streams

private[streams] trait FlatMapOps
    extends ClosureStreamOps
    with CanBuildFromSinks
    with Streams
    with Strippers {
  val global: scala.reflect.api.Universe
  import global._

  val SomeStream: Extractor[Tree, Stream]

  object SomeFlatMapOp extends StreamOpExtractor {
    override def unapply(tree: Tree) = tree match {
      case q"$target.flatMap[$tpt, ${ _ }](${ Closure(closure) })($cbf)" =>
        ExtractedStreamOp(target, FlatMapOp(tpt.tpe, closure, Some(cbf)))

      // Option.flatMap doesn't take a CanBuildFrom.
      case q"$target.flatMap[$tpt](${ Closure(closure) })" =>
        ExtractedStreamOp(target, FlatMapOp(tpt.tpe, closure, None))

      case _ =>
        NoExtractedStreamOp
    }
  }

  case class FlatMapOp(tpe: Type, closure: Function, canBuildFrom: Option[Tree])
      extends ClosureStreamOp {
    override def stripBody(tree: Tree) = stripOption2Iterable(tree)

    val nestedStream: Option[Stream] = q"($param) => $body" match {
      case SomeTransformationClosure(
        TransformationClosure(
          _,
          Nil,
          ScalarValue(_, Some(SomeStream(stream)), _),
          _)) if stream.sink.canBeElided =>
        Some(stream)

      case _ =>
        None // TODO
    }

    override def describe = Some(
      "flatMap" + nestedStream.map("(" + _.describe(describeSink = false) + ")").getOrElse("")
    )

    override def lambdaCount = 1 + nestedStream.map(_.lambdaCount).getOrElse(0)

    override def subTrees =
      nestedStream.map(_.subTrees).
        getOrElse(super.subTrees)

    override def preservedSubTrees = Nil

    override def closureSideEffectss =
      nestedStream.map(_.closureSideEffectss).
        getOrElse(super.closureSideEffectss)

    // Do not leak the interruptibility out of a flatMap.
    override def canInterruptLoop = false //nestedStream.map(_.ops.exists(_.canInterruptLoop)).getOrElse(false)

    override def canAlterSize = true

    override def transmitOutputNeedsBackwards(paths: Set[TuploidPath]) = {
      val sup = super.transmitOutputNeedsBackwards(paths)
      val nested = nestedStream.map(stream => {
        val nestedNeeds = stream.ops.foldRight(paths)({
          case (op, refs) =>
            op.transmitOutputNeedsBackwards(refs)
        })

        nestedNeeds

      }).getOrElse(Set())

      val result = (nested ++ sup).filter(transformationClosure.inputs.exists(_))
      // println(s"""
      //   FlatMapOp.transmitOutputNeedsBackwards($paths) = $result
      //     nestedStream.desc: ${nestedStream.map(_.describe())}
      //     nestedStream: $nestedStream
      //     sup: $sup
      //     nested: $nested
      //     closure: $closure
      // """)
      result
    }

    override val sinkOption = canBuildFrom.map(CanBuildFromSink(_))

    override def emit(
      input: StreamInput,
      outputNeeds: OutputNeeds,
      nextOps: OpsAndOutputNeeds
    ): StreamOutput =
      {
        import input.{ fresh, transform, typed, currentOwner }

        nestedStream match {
          case Some(stream) =>
            val replacer = getReplacer(transformationClosure.inputs, input.vars)
            val subTransformer = new Transformer {
              override def transform(tree: Tree) = {
                if (tree.symbol == param.symbol) {
                  input.vars.alias.get.duplicate
                } else {
                  super.transform(tree)
                }
              }
            }
            val subTransform = (tree: Tree) => subTransformer.transform(transform(tree))

            val modifiedStream = {
              val (outerSink: StreamSink) :: outerOpsRev = nextOps.map(_._1).reverse
              val outerOps = outerOpsRev.reverse

              stream.copy(ops = stream.ops ++ outerOps, sink = outerSink)
            }

            modifiedStream.emitStream(
              fresh, subTransform,
              currentOwner = currentOwner,
              typed = typed,
              loopInterruptor = input.loopInterruptor
            ).map(replacer)

          case _ =>
            val (replacedStatements, outputVars) =
              transformationClosure.replaceClosureBody(
                input.copy(
                  outputSize = None,
                  index = None
                ),
                outputNeeds
              )

            val itemVal = fresh("item")
            val Function(List(itemValDef @ ValDef(_, _, _, _)), itemValRef @ Ident(_)) = typed(q"""
            ($itemVal: $tpe) => $itemVal
          """)

            val sub = emitSub(
              input.copy(
                vars = ScalarValue(tpe, alias = Some(itemValRef)),
                outputSize = None,
                index = None
              ),
              nextOps
            )
            sub.copy(body = List(typed(q"""
            ..$replacedStatements;
            // TODO: plug that lambda's symbol as the new owner of sub.body's decls.
            ${outputVars.alias.get}.foreach(($itemValDef) => {
              ..${sub.body};
            })
          """)))
        }
      }
  }
}

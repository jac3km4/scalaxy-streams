package scalaxy.streams

private[streams] trait MkStringOps
    extends UnusableSinks
    with OptionSinks
    with Streams {
  val global: scala.reflect.api.Universe
  import global._

  object SomeMkStringOp extends StreamOpExtractor {
    private[this] def isString(tpe: Type): Boolean =
      tpe != null && tpe <:< typeOf[String]

    override def unapply(tree: Tree) = {
      if (isString(tree.tpe)) {
        tree match {
          case q"$target.mkString" =>
            ExtractedStreamOp(target, MkStringOp(None, None, None))

          case q"$target.mkString($sep)" =>
            ExtractedStreamOp(target, MkStringOp(None, Some(sep), None))

          case q"$target.mkString($start, $sep, $end)" =>
            ExtractedStreamOp(target, MkStringOp(Some(start), Some(sep), Some(end)))

          case _ =>
            NoExtractedStreamOp
        }
      } else {
        NoExtractedStreamOp
      }
    }
  }

  case class MkStringOp(
    start: Option[Tree],
      sep: Option[Tree],
      end: Option[Tree]
  ) extends StreamOp {
    override def lambdaCount = 0
    override def sinkOption = Some(ScalarSink)
    override def canAlterSize = false
    override def describe = Some("mkString")
    override def subTrees: List[Tree] =
      start.toList ++ sep ++ end
    override def transmitOutputNeedsBackwards(paths: Set[TuploidPath]) =
      Set(RootTuploidPath)

    override def emit(input: StreamInput, outputNeeds: OutputNeeds, nextOps: OpsAndOutputNeeds): StreamOutput =
      {
        import input._

        // TODO: remove this to unlock flatMap
        val List((ScalarSink, _)) = nextOps

        val startVal = fresh("start")
        val sepVal = fresh("sep")
        val endVal = fresh("end")
        val firstVar = fresh("first")
        val builderVal = fresh("builder")

        require(input.vars.alias.nonEmpty, s"input.vars = $input.vars")

        def emptyString: Tree = q""" "" """

        val Block(List(
          startDef,
          sepDef,
          endDef,
          builderDef,
          firstDef,
          appendStart,
          appendSep,
          appendInput,
          appendEnd), result) = typed(q"""
        private[this] val $startVal: String = ${start.getOrElse(emptyString)};
        private[this] val $sepVal: String = ${sep.getOrElse(emptyString)};
        private[this] val $endVal: String = ${end.getOrElse(emptyString)};
        private[this] val $builderVal = new scala.collection.mutable.StringBuilder();
        private[this] var $firstVar: Boolean = true;
        $builderVal.append($startVal);
        if ($firstVar) {
          $firstVar = false;
        } else {
          $builderVal.append($sepVal);
        }
        $builderVal.append(${input.vars.alias.get});
        $builderVal.append($endVal);
        $builderVal.result()
      """)

        (start, sep, end) match {
          case (None, None, None) =>
            StreamOutput(
              prelude = List(builderDef),
              body = List(appendInput),
              ending = List(result)
            )

          case (None, Some(_), None) =>
            StreamOutput(
              prelude = List(builderDef, firstDef, sepDef),
              body = List(appendSep, appendInput),
              ending = List(result)
            )

          case _ =>
            StreamOutput(
              prelude = List(builderDef, firstDef, startDef, sepDef, endDef, appendStart),
              body = List(appendSep, appendInput),
              ending = List(appendEnd, result)
            )
        }
      }
  }
}

// def mkString(start: String, sep: String, end: String): String =
//   addString(new StringBuilder(), start, sep, end).toString

// def mkString(sep: String): String = mkString("", sep, "")

// def mkString: String = mkString("")

// def addString(b: StringBuilder, start: String, sep: String, end: String): StringBuilder = {
//   var first = true

//   b append start
//   for (x <- self) {
//     if (first) {
//       b append x
//       first = false
//     }
//     else {
//       b append sep
//       b append x
//     }
//   }
//   b append end

//   b
// }

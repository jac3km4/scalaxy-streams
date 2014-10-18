package scalaxy.streams

private[streams] trait Streams
    extends StreamComponents
    with UnusableSinks
{
  val global: scala.reflect.api.Universe
  import global._

  object SomeStream extends Extractor[Tree, Stream] {
    def findSink(ops: List[StreamComponent]): Option[StreamSink] = {
      ops.reverse.toIterator.zipWithIndex.map({
        case (op, i) => (op.sinkOption, i)
      }) collectFirst {
        case (Some(sink), i) if !sink.isFinalOnly || i == 0 =>
          sink
      }
    }

    def unapply(tree: Tree): Option[Stream] = tree match {
      case SomeStreamSink(SomeStreamOp(SomeStreamSource(source), ops), sink) =>
        Some(new Stream(source, ops, sink, hasExplicitSink = true))

      case SomeStreamOp(SomeStreamSource(source), ops) =>
        findSink(source :: ops).filter(_ != InvalidSink)
          .map(sink => new Stream(source, ops, sink, hasExplicitSink = false))

      case SomeStreamSource(source) =>
        findSink(List(source)).filter(_ != InvalidSink)
          .map(sink => new Stream(source, Nil, sink, hasExplicitSink = false))

      case _ =>
        None
    }
  }

  case class Stream(
      source: StreamSource,
      ops: List[StreamOp],
      sink: StreamSink,
      hasExplicitSink: Boolean)
  {
    def describe(describeSink: Boolean = true) =
      (source :: ops).flatMap(_.describe).mkString(".") +
      sink.describe.filter(_ => describeSink).map(" -> " + _).getOrElse("")

    def lambdaCount = ((source :: ops) :+ sink).map(_.lambdaCount).sum

    // TODO: refine this.
    def isWorthOptimizing(strategy: OptimizationStrategy) = {
      val result = strategy match {

        case scalaxy.streams.optimization.safe =>
          // TODO: side-effects analysis to allow more cases where lambdaCount > 1
          lambdaCount == 1

        case scalaxy.streams.optimization.aggressive =>
          lambdaCount >= 1

        case scalaxy.streams.optimization.foolish =>
          ops.length > 0 || hasExplicitSink
          // true

        case _ =>
          assert(strategy == scalaxy.streams.optimization.none)
          false
      }
      result
    }

    def emitStream(fresh: String => TermName,
                   transform: Tree => Tree,
                   typed: Tree => Tree,
                   currentOwner: Symbol,
                   untyped: Tree => Tree,
                   sinkNeeds: Set[TuploidPath] = sink.outputNeeds,
                   loopInterruptor: Option[Tree] = None): StreamOutput =
    {
      val sourceNeeds :: outputNeeds = ops.scanRight(sinkNeeds)({ case (op, refs) =>
        op.transmitOutputNeedsBackwards(refs)
      })
      val nextOps = ops.zip(outputNeeds) :+ (sink, sinkNeeds)
      // println(s"source = $source")
      // println(s"""ops =\n\t${ops.map(_.getClass.getName).mkString("\n\t")}""")
      // println(s"outputNeeds = ${nextOps.map(_._2)}")
      source.emit(
        input = StreamInput(
          vars = UnitTreeScalarValue,
          loopInterruptor = loopInterruptor,
          fresh = fresh,
          transform = transform,
          currentOwner = currentOwner,
          typed = typed,
          untyped = untyped),
        outputNeeds = sourceNeeds,
        nextOps = nextOps)
    }
  }
}

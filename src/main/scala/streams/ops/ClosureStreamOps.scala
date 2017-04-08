package scalaxy.streams

private[streams] trait ClosureStreamOps
    extends StreamComponents
    with TransformationClosures {
  val global: scala.reflect.api.Universe
  import global._

  trait ClosureStreamOp extends StreamOp {
    def closure: Function
    def isMapLike: Boolean = true
    override def lambdaCount = 1
    override def subTrees: List[Tree] = List(closure)
    override def preservedSubTrees = Nil

    private[this] lazy val closureSideEffects = analyzeSideEffects(closure)
    override def closureSideEffectss = List(closureSideEffects)

    lazy val closureSymbol = closure.symbol

    // TODO: remove this stripBody nonsense (here to allow FlatMapOps to do some magics)
    //lazy val q"($param) => $body_" = transformationClosure
    lazy val q"($param) => $body_" = closure
    lazy val body = stripBody(body_)
    def stripBody(tree: Tree): Tree = tree

    lazy val SomeTransformationClosure(transformationClosure) = closure // q"($param) => $body"

    override def transmitOutputNeedsBackwards(paths: Set[TuploidPath]) =
      transformationClosure.getPreviousReferencedPaths(paths, isMapLike = isMapLike)
  }
}

package scalaxy.streams

private[streams] trait StreamOps
    extends ArrayOpsOps
    with CoerceOps
    with CollectOps
    with CountOps
    with FilterOps
    with FindOps
    with ExistsOps
    with FlattenOps
    with FlatMapOps
    with ForeachOps
    with IsEmptyOps
    with JsArrayOpsOps
    with MapOps
    with MkStringOps
    with OptionOps
    with ReductionOps
    with ToCollectionOps
    with TakeDropOps
    with TakeWhileOps
    with ZipWithIndexOps
{
  val global: scala.reflect.api.Universe
  import global._

  object SomeStreamOps extends Extractor[Tree, (Tree, List[StreamOp])] {
    val extractors = List[StreamOpExtractor](
      SomeArrayOpsOp,
      SomeCoerceOp,
      // TODO: fix intractable typing issues with case classes:
      //   SomeCollectOp,
      SomeCountOp,
      SomeExistsOp,
      SomeFilterOp,
      SomeFindOp,
      // SomeFlattenOp,
      SomeFlatMapOp,
      SomeForeachOp,
      SomeIsEmptyOp,
      SomeJsArrayOpsOp,
      SomeMapOp,
      SomeMkStringOp,
      SomeOptionOp,
      SomeReductionOp,
      SomeTakeDropOp,
      SomeTakeWhileOp,
      SomeToCollectionOp,
      SomeZipWithIndexOp
    )

    object ExtractOps {
      def unapply(extractorAndTree: (StreamOpExtractor, Tree)): Option[(Tree, List[StreamOp])] = {
        val (extractor, tree) = extractorAndTree
        extractor.unapply(tree) collect {
          case (SomeStreamOps(src, ops), op)
              if !ops.lastOption.exists(_.sinkOption == Some(ScalarSink)) =>
            (src, ops :+ op)

          case (src, op) =>
            (src, List(op))
        }
      }
    }

    def unapply(tree: Tree): Option[(Tree, List[StreamOp])] = {
      extractors.toIterator.map(x => (x, tree)).collectFirst({
        case ExtractOps(src, ops) =>
          (src, ops)
      })
    }
  }
}

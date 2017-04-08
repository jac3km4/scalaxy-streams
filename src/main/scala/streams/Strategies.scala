package scalaxy.streams

import scala.language.existentials

private[streams] trait Strategies
    extends Streams
    with SideEffectsDetection
    with Reporters {
  self: StreamTransforms =>

  val global: scala.reflect.api.Universe
  import global._

  def hasKnownLimitationOrBug(stream: Stream): Boolean = {

    def hasTakeOrDrop: Boolean = stream.ops.exists({
      case TakeWhileOp(_, _) | DropWhileOp(_, _) | TakeOp(_) | DropOp(_) =>
        true

      case _ =>
        false
    })

    // Detects two potentially-related issues.
    def hasTryOrByValueSubTrees: Boolean = stream.components.exists(_.subTrees.exists {
      case Try(_, _, _) =>
        // This one is... interesting.
        // Something horrible (foo not found) happens to the following snippet in lambdalift:
        //
        //     val msg = {
        //       try {
        //         val foo = 10
        //         Some(foo)
        //       } catch {
        //         case ex: Throwable => None
        //       }
        //     } get;
        //     msg
        //
        // I'm being super-mega cautious with try/catch here, until the bug is understood / fixed.
        true

      case t @ Apply(target, args) if Option(t.symbol).exists(_.isMethod) =>
        // If one of the subtrees is a method call with by-name params, then
        // weird symbol ownership issues arise (x not found in the following snippet)
        //
        //     def wrap[T](body: => T): Option[T] = Option(body)
        //     wrap({ val x = 10; Option(x) }) getOrElse 0
        //
        t.symbol.asMethod.paramLists.exists(_.exists(_.asTerm.isByNameParam))

      case _ =>
        false
    })

    def isRangeTpe(tpe: Type): Boolean =
      tpe <:< typeOf[Range] ||
        tpe <:< typeOf[collection.immutable.NumericRange[_]]

    def isOptionTpe(tpe: Type): Boolean =
      tpe <:< typeOf[Option[_]]

    def isWithFilterOp(op: StreamOp): Boolean = op match {
      case CoerceOp(_) => true
      case WithFilterOp(_) => true
      case _ => false
    }

    def streamTpe: Option[Type] = findType(stream.tree)

    stream.source match {
      case RangeStreamSource(_) if hasTakeOrDrop && streamTpe.exists(isRangeTpe) =>
        // Range.take / drop / takeWhile / dropWhile return Ranges: not handled yet.
        true

      case OptionStreamSource(_) if hasTakeOrDrop && streamTpe.exists(isOptionTpe) =>
        // Option.take / drop / takeWhile / dropWhile return Lists: not handled yet.
        true

      case _ if stream.ops.lastOption.exists(isWithFilterOp) =>
        // Option.withFilter returns an Option#WithFilter
        true

      case _ if !stream.sink.isImplemented =>
        true

      case _ if hasTryOrByValueSubTrees =>
        true

      case _ =>
        false
    }
  }

  // TODO: refine this.
  def isWorthOptimizing(
    stream: Stream,
    strategy: OptimizationStrategy
  ) = {
    (strategy.speedup != SpeedupCriteria.Never) &&
      !stream.isDummy && {
        var reportedSideEffects = Set[SideEffect]()

        val safeSeverities: Set[SideEffectSeverity] = strategy.safety match {
          case SafetyCriteria.Safe =>
            Set()

          case SafetyCriteria.ProbablySafe =>
            Set(SideEffectSeverity.ProbablySafe)

          case SafetyCriteria.Unsafe =>
            Set(SideEffectSeverity.ProbablySafe, SideEffectSeverity.Unsafe)
        }

        // println(s"safeSeverities(strategy: $strategy) = $safeSeverities")

        def hasUnsafeEffect(effects: List[SideEffect]): Boolean =
          effects.exists(e => !safeSeverities(e.severity))

        def couldSkipSideEffects: Boolean = {
          var foundCanInterruptLoop = false
          for (op <- stream.ops.reverse) {
            if (op.canInterruptLoop || op.canAlterSize) {
              foundCanInterruptLoop = true
            } else {
              if (foundCanInterruptLoop &&
                hasUnsafeEffect(op.closureSideEffectss.flatten)) {
                return true
              }
            }
          }
          return false
        }

        def reportIgnoredUnsafeSideEffects(): Unit = if (!flags.quietWarnings) {
          for (
            effects <- stream.closureSideEffectss ++ stream.preservedSubTreesSideEffectss;
            effect <- effects;
            if effect.severity == SideEffectSeverity.Unsafe
          ) {
            reportedSideEffects += effect
            warning(effect.tree.pos, Optimizations.messageHeader +
              s"Potential side effect could cause issues with ${strategy.name} optimization strategy: ${effect.description}")
          }
        }

        def hasTakeOrDropWhileOp: Boolean = stream.ops.exists({
          case TakeWhileOp(_, _) | DropWhileOp(_, _) => true
          case _ => false
        })

        def isKnownNotToBeFaster = stream match {
          case Stream(_, ListStreamSource(_, _, _), _, _, _) if stream.lambdaCount == 1 =>
            // List operations are now quite heavily optimized. It only makes sense to
            // rewrite more than one operation.
            true

          case Stream(_, ArrayStreamSource(_, _, _), ops, _, _) if stream.lambdaCount == 1 &&
            hasTakeOrDropWhileOp =>
            // Array.takeWhile / .dropWhile needs to be optimized better :-)
            true

          case Stream(_, source, ops, sink, _) =>
            false
        }

        // Note: we count the number of closures / ops that have side effects, not the
        // number of side-effects themselves: we assume that within a closure the 
        // side effects are still done in the same order, and likewise for preserved
        // sub-trees that they're still evaluated in the same order within the same
        // originating op. For instance with mkString(prefix, sep, suffix), prefix,
        // sep and suffix will still be evaluated in the same order after the rewrite.
        val unsafeClosureSideEffectCount =
          stream.closureSideEffectss.count(hasUnsafeEffect)
        def unsafePreservedTreesSideEffectsCount =
          stream.preservedSubTreesSideEffectss.count(hasUnsafeEffect)

        def isStreamSafe = {
          unsafeClosureSideEffectCount <= 1 &&
            (unsafeClosureSideEffectCount + unsafePreservedTreesSideEffectsCount) <= 1 &&
            !couldSkipSideEffects
        }

        // At least one lambda.
        def isFaster =
          !isKnownNotToBeFaster &&
            stream.lambdaCount >= 1

        val isStrategyUnsafe =
          strategy.safety == SafetyCriteria.Unsafe

        if (isStrategyUnsafe) {
          reportIgnoredUnsafeSideEffects()
        }

        val worthOptimizing =
          (isStrategyUnsafe || isStreamSafe) &&
            ((strategy.speedup == SpeedupCriteria.AlwaysEvenIfSlower) || isFaster)

        if (flags.veryVerbose) {
          for (
            effects <- stream.closureSideEffectss;
            effect <- effects;
            if !reportedSideEffects(effect)
          ) {
            info(effect.tree.pos, Optimizations.messageHeader + s"Side effect: ${effect.description} (${effect.severity.description})")
          }
        }

        // if (flags.debug) {
        //   // info(stream.tree.pos, 
        //   println(s"""
        //     tree = ${stream.tree}
        //     stream = ${stream.describe()}
        //     strategy = $strategy
        //     lambdaCount = ${stream.lambdaCount}
        //     closureSideEffectss = ${stream.closureSideEffectss}
        //     couldSkipSideEffects = $couldSkipSideEffects
        //     isWorthOptimizing = $worthOptimizing
        //     isFaster = $isFaster
        //     unsafeClosureSideEffectCount = $unsafeClosureSideEffectCount
        //     unsafePreservedTreesSideEffectsCount = $unsafePreservedTreesSideEffectsCount
        //     isStreamSafe = $isStreamSafe
        //   """)//, force = true)
        // }

        worthOptimizing
      }
  }
}

package scalaxy.streams
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer

import scalaxy.streams.SideEffectsWhitelists._

trait SideEffectsDetection
    extends Streams
    with SideEffectsMessages {
  val global: scala.reflect.api.Universe
  import global._

  implicit class RichSymbol(sym: Symbol) {
    def enclosingPackage: Symbol = {
      var sym = this.sym
      while (sym != NoSymbol && !sym.isPackageClass && !sym.isPackage)
        sym = sym.owner
      sym
    }
    def enclosingClass: Symbol = {
      def encl(sym: Symbol): Symbol =
        if (sym.isClass || sym == NoSymbol) sym else encl(sym.owner)
      encl(this.sym)
    }
  }

  private[this] def isBlacklisted(sym: Symbol): Boolean =
    blacklistedMethods(sym.fullName)

  private[this] def isSideEffectFree(sym: Symbol): Boolean = {
    val result =
      sym.isPackage ||
        sym.isTerm && {
          val tsym = sym.asTerm
          tsym.isVal && tsym.isStable && !tsym.isLazy
        } ||
        whitelistedSymbols(sym.fullName) ||
        whitelistedClasses(sym.enclosingClass.fullName) ||
        whitelistedPackages(sym.enclosingPackage.fullName)

    // if (sym.isTerm) {
    //   val tsym = sym.asTerm
    //   println(s"""
    //     sym = $sym
    //     isFinal = ${tsym.isFinal}
    //     isGetter = ${tsym.isGetter}
    //     isLazy = ${tsym.isLazy}
    //     isStable = ${tsym.isStable}
    //     isVal = ${tsym.isVal}
    //     result = $result
    //   """)
    // }

    result
  }

  def isTrulyImmutableClass(tpe: Type): Boolean = tpe != null && {
    val sym = tpe.typeSymbol
    val result =
      sym != null && sym != NoSymbol &&
        trulyImmutableClasses(sym.fullName)

    // println(s"isTrulyImmutableClass($sym) = $result")
    result
  }

  object SelectOrApply {
    def unapply(tree: Tree): Option[(Tree, Name, List[Tree], List[List[Tree]])] =
      Option(tree) collect {
        case Ident(name) =>
          (EmptyTree, name, Nil, Nil)

        case Select(target, name) =>
          (target, name, Nil, Nil)

        case TypeApply(SelectOrApply(target, name, Nil, Nil), targs) =>
          // case TypeApply(Select(target, name), targs) =>
          (target, name, targs, Nil)

        case Apply(SelectOrApply(target, name, targs, argss), newArgs) =>
          (target, name, targs, argss :+ newArgs)
      }
  }

  private[this] lazy val ArrayModuleSym = rootMirror.staticModule("scala.Array")

  def analyzeSideEffects(tree: Tree): List[SideEffect] = {
    val effects = ArrayBuffer[SideEffect]()
    def addEffect(e: SideEffect) {
      effects += e
    }
    var localSymbols = Set[Symbol]()
    new Traverser {
      override def traverse(tree: Tree) {
        tree match {
          case (_: DefTree) | ValDef(_, _, _, _) if Option(tree.symbol).exists(_ != NoSymbol) =>
            localSymbols += tree.symbol
          case _ =>
        }
        super.traverse(tree)
      }
    } traverse tree

    def keepWorstSideEffect(block: => Unit) {
      val size = effects.size
      block
      if (effects.size > size) {
        val slice = effects.slice(size, effects.size)
        val worstSeverity = slice.map(_.severity).max
        var worstEffect = effects.find(_.severity == worstSeverity).get
        effects.remove(size, effects.size - size)
        effects += worstEffect

        // println(s"""
        //   Side-Effect:
        //     tree: ${worstEffect.tree}
        //     tree.symbol = ${worstEffect.tree.symbol} (${Option(worstEffect.tree.symbol).map(_.fullName)}}
        //     description: ${worstEffect.description}
        //     severity: ${worstEffect.severity}
        // """)
      }
    }

    // println("TRAVERSING " + tree)

    new Traverser {
      override def traverse(tree: Tree) {
        tree match {
          case SomeStream(stream) if !stream.ops.isEmpty || stream.hasExplicitSink =>
            // println("FOUND stream " + stream)
            for (sub <- stream.subTrees; if tree != sub) {
              assert(tree != sub, s"stream = $stream, sub = $sub")

              traverse(sub)
            }

          case SelectOrApply(qualifier, N(name @ ("hashCode" | "toString")), Nil, Nil | List(Nil)) if !isTrulyImmutableClass(qualifier.tpe) =>
            keepWorstSideEffect {
              addEffect(SideEffect(tree, anyMethodMessage(name), SideEffectSeverity.ProbablySafe))
              traverse(qualifier)
            }

          case SelectOrApply(qualifier, N(name @ ("equals" | "$eq$eq" | "$bang$eq" | "ne")), Nil, List(List(other))) if !isTrulyImmutableClass(qualifier.tpe) || name == "ne" =>
            keepWorstSideEffect {
              if (name != "ne") {
                addEffect(SideEffect(tree, anyMethodMessage(name), SideEffectSeverity.ProbablySafe))
              }
              traverse(qualifier)
              traverse(other)
            }
          case q"$arr.apply[${ _ }](..$args)" if arr.symbol == ArrayModuleSym =>
            // Special case to handle partially-symbolized tree from SomeInlineSeqStreamSource.
            // TODO(ochafi): Update SomeInlineSeqStreamSource to switch to an ArrayStreamSource during emit (where trees can be typed).
            args.foreach(traverse(_))

          case SelectOrApply(qualifier, name, _, argss) =>
            keepWorstSideEffect {
              val sym = tree.symbol

              def qualifierIsImmutable =
                qualifier != EmptyTree &&
                  qualifier.tpe != null &&
                  qualifier.tpe != NoType &&
                  isTrulyImmutableClass(qualifier.tpe)

              val safeSymbol =
                !isBlacklisted(sym) &&
                  (
                    localSymbols.contains(sym) ||
                    isSideEffectFree(sym) ||
                    qualifierIsImmutable
                  )

              // if (safeSymbol) {
              //   println(s"""
              //     SAFE SYMBOL(${sym.fullName})
              //       qualifier: $qualifier
              //       qualifier.tpe: ${qualifier.tpe}
              //       qualifier.tpe.typeSymbol: ${qualifier.tpe.typeSymbol}
              //       name: $name
              //       localSymbols.contains(sym): ${localSymbols.contains(sym)}
              //       isSideEffectFree(sym): ${isSideEffectFree(sym)}
              //       isSideEffectFree(qualifier.tpe.typeSymbol): ${isSideEffectFree(qualifier.tpe.typeSymbol)}
              //   """)
              // }
              if (!safeSymbol) {
                (name, argss) match {
                  case (ProbablySafeUnaryNames(msg), (List(_) :: _)) =>
                    addEffect(SideEffect(tree, msg + " (symbol: " + sym.fullName + ")", SideEffectSeverity.ProbablySafe))

                  case _ =>
                    addEffect(
                      SideEffect(
                        tree,
                        if (sym == NoSymbol)
                          "Reference with no symbol: " + tree
                        else
                          "Reference to " + sym.fullName, // (local symbols: $localSymbols",
                        SideEffectSeverity.Unsafe
                      )
                    )
                }
              }

              traverse(qualifier)
              argss.foreach(_.foreach(traverse(_)))
            }

          case Assign(_, _) | Function(_, _) |
            TypeTree() | EmptyTree |
            Literal(_) | Block(_, _) |
            Match(_, _) | Typed(_, _) | This(_) |
            (_: DefTree) =>
            super.traverse(tree)

          case CaseDef(_, guard, body) =>
            traverse(guard)
            traverse(body)

          case If(cond, thenp, elsep) =>
            traverse(cond)
            traverse(thenp)
            traverse(elsep)

          case Throw(ex) =>
            addEffect(SideEffect(tree, "Throw statement", SideEffectSeverity.Unsafe))
            super.traverse(tree)

          case _ =>
            val msg = s"TODO: proper message for ${tree.getClass.getName}: $tree"

            addEffect(SideEffect(tree, msg, SideEffectSeverity.Unsafe))
            super.traverse(tree)
        }
      }
    } traverse tree

    // println(s"SIDE EFFECTS $tree: ${effects.size}")
    // for (e <- effects) {
    //   println(s"\t$e")
    // }

    effects.toList
  }
}

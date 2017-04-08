package scalaxy

import scala.language.reflectiveCalls

import scala.language.experimental.macros
import scala.language.implicitConversions

import scala.reflect.NameTransformer
import scala.reflect.macros.blackbox.Context

import scala.reflect.runtime.{ universe => ru }

import scalaxy.streams.HacksAndWorkarounds.cast

package object streams {
  def optimize[A](a: A): A = macro impl.recursivelyOptimize[A]
}

package streams {
  object impl {
    def recursivelyOptimize[A: c.WeakTypeTag](c: Context)(a: c.Expr[A]): c.Expr[A] = {
      optimize[A](c)(a, recurse = true)
    }

    def optimizeTopLevelStream[A: c.WeakTypeTag](c: Context)(a: c.Expr[A]): c.Expr[A] = {
      optimize[A](c)(a, recurse = false)
    }

    private[streams] def optimize[A: c.WeakTypeTag](c: Context)(a: c.Expr[A], recurse: Boolean): c.Expr[A] = {

      if (flags.disabled) {
        a
      } else {
        object Optimize extends StreamTransforms with WithMacroContext with Optimizations {
          override val context = c
          import global._

          val strategy = matchStrategyTree(
            tpe => c.inferImplicitValue(
              tpe.asInstanceOf[c.Type],
              pos = a.tree.pos.asInstanceOf[c.Position]
            ).asInstanceOf[Tree]
          )

          val result = try {

            c.internal.typingTransform(cast(a.tree))((tree_, api) => {
              val tree: Tree = cast(tree_)

              // TODO(ochafik): Remove these warts (needed because of dependent types mess).
              def apiDefault(tree: Tree): Tree = cast(api.default(cast(tree)))
              def apiRecur(tree: Tree): Tree =
                if (recurse)
                  cast(api.recur(cast(tree)))
                else
                  tree
              def apiTypecheck(tree: Tree): Tree = cast(api.typecheck(cast(tree)))

              def opt(tree: Tree) =
                transformStream(
                  tree = tree,
                  strategy = strategy,
                  fresh = c.freshName(_),
                  currentOwner = cast(api.currentOwner),
                  recur = apiRecur,
                  typecheck = apiTypecheck
                )

              val result = opt(tree).getOrElse {
                if (recurse) {
                  val sup = apiDefault(tree)
                  opt(sup).getOrElse(sup)
                } else {
                  tree
                }
              }

              // println(s"result = $result")
              result.asInstanceOf[c.universe.Tree]
            })
          } catch {
            case ex: Throwable =>
              logException(cast(a.tree.pos), ex)
              a.tree
          }
        }

        c.Expr[A](Optimize.result)
      }
    }
  }
}

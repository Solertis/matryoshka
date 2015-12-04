/*
 * Copyright 2014 - 2015 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.fs

import quasar.LogicalPlan, LogicalPlan.ReadF
import quasar.fp.free._
import quasar.recursionschemes._, FunctorT.ops._

import monocle.Optional
import monocle.function.Field1
import monocle.std.tuple2._
import pathy.{Path => PPath}, PPath._
import scalaz._
import scalaz.std.tuple._
import scalaz.syntax.functor._

object chroot {

  /** Rebases all paths in `ReadFile` operations onto the given prefix. */
  def readFile[S[_]: Functor](prefix: ADir)
                             (implicit S: ReadFileF :<: S)
                             : S ~> S = {
    import ReadFile._

    val g = new (ReadFile ~> ReadFileF) {
      def apply[A](rf: ReadFile[A]) = rf match {
        case Open(src, off, lim) =>
          Coyoneda.lift(Open(rebaseA(src, prefix), off, lim))
            .map(_ leftMap stripPathError(prefix))

        case Read(h) =>
          Coyoneda.lift(Read(h))
            .map(_ leftMap stripPathError(prefix))

        case Close(h) =>
          Coyoneda.lift(Close(h))
      }
    }

    injectedNT[ReadFileF, S](Coyoneda.liftTF(g))
  }

  /** Rebases all paths in `WriteFile` operations onto the given prefix. */
  def writeFile[S[_]: Functor](prefix: ADir)
                              (implicit S: WriteFileF :<: S)
                              : S ~> S = {
    import WriteFile._

    val g = new (WriteFile ~> WriteFileF) {
      def apply[A](wf: WriteFile[A]) = wf match {
        case Open(dst) =>
          Coyoneda.lift(Open(rebaseA(dst, prefix)))
            .map(_ leftMap stripPathError(prefix))

        case Write(h, d) =>
          Coyoneda.lift(Write(h, d))
            .map(_ map stripPathError(prefix))

        case Close(h) =>
          Coyoneda.lift(Close(h))
      }
    }

    injectedNT[WriteFileF, S](Coyoneda.liftTF(g))
  }

  /** Rebases all paths in `ManageFile` operations onto the given prefix. */
  def manageFile[S[_]: Functor](prefix: ADir)
                               (implicit S: ManageFileF :<: S)
                               : S ~> S = {
    import ManageFile._, MoveScenario._

    val g = new (ManageFile ~> ManageFileF) {
      def apply[A](mf: ManageFile[A]) = mf match {
        case Move(scn, sem) =>
          Coyoneda.lift(Move(
            scn.fold(
              (src, dst) => DirToDir(rebaseA(src, prefix), rebaseA(dst, prefix)),
              (src, dst) => FileToFile(rebaseA(src, prefix), rebaseA(dst, prefix))),
            sem))
            .map(_ leftMap stripPathError(prefix))

        case Delete(p) =>
          Coyoneda.lift(Delete(rebaseA(p, prefix)))
            .map(_ leftMap stripPathError(prefix))

        case TempFile(p) =>
          Coyoneda.lift(TempFile(rebaseA(p, prefix)))
            .map(_ bimap (stripPathError(prefix), stripPrefixA(prefix)))
      }
    }

    injectedNT[ManageFileF, S](Coyoneda.liftTF(g))
  }

  /** Rebases paths in `QueryFile` onto the given prefix. */
  def queryFile[S[_]: Functor](prefix: ADir)
                              (implicit S: QueryFileF :<: S)
                              : S ~> S = {
    import QueryFile._

    val base = Path(posixCodec.printPath(prefix))

    val rebasePlan: LogicalPlan ~> LogicalPlan =
      new (LogicalPlan ~> LogicalPlan) {
        def apply[A](lp: LogicalPlan[A]) = lp match {
          case ReadF(p) => ReadF(base ++ p)
          case _        => lp
        }
      }

    val g = new (QueryFile ~> QueryFileF) {
      def apply[A](qf: QueryFile[A]) = qf match {
        case ExecutePlan(lp, out) =>
          Coyoneda.lift(ExecutePlan(lp.translate(rebasePlan), rebaseA(out, prefix)))
            .map(_.map(_.bimap(stripPathError(prefix), stripPrefixA(prefix))))

        case EvaluatePlan(lp) =>
          Coyoneda.lift(EvaluatePlan(lp.translate(rebasePlan)))
            .map(_.map(_ leftMap stripPathError(prefix)))

        case More(h) =>
          Coyoneda.lift(More(h))
            .map(_ leftMap stripPathError(prefix))

        case Close(h) =>
          Coyoneda.lift(Close(h))

        case Explain(lp) =>
          Coyoneda.lift(Explain(lp.translate(rebasePlan)))
            .map(_.map(_ leftMap stripPathError(prefix)))

        case ListContents(d) =>
          Coyoneda.lift(ListContents(rebaseA(d, prefix)))
            .map(_.bimap(stripPathError(prefix), _ map stripNodePrefix(prefix)))

        case FileExists(f) =>
          Coyoneda.lift(FileExists(rebaseA(f, prefix)))
            .map(_ leftMap stripPathError(prefix))
      }
    }

    injectedNT[QueryFileF, S](Coyoneda.liftTF(g))
  }

  /** Rebases all paths in `FileSystem` operations onto the given prefix. */
  def fileSystem[S[_]: Functor](prefix: ADir)
                               (implicit S0: ReadFileF :<: S,
                                         S1: WriteFileF :<: S,
                                         S2: ManageFileF :<: S,
                                         S3: QueryFileF :<: S)
                               : S ~> S = {

    readFile[S](prefix)   compose
    writeFile[S](prefix)  compose
    manageFile[S](prefix) compose
    queryFile[S](prefix)
  }

  ////

  private val fsPathError: Optional[FileSystemError, APath] =
    FileSystemError.pathError composeLens PathError2.errorPath

  private val fsPlannerError: Optional[FileSystemError, Fix[LogicalPlan]] =
    FileSystemError.plannerError composeLens Field1.first

  private def stripPathError(prefix: ADir): FileSystemError => FileSystemError = {
    val base = Path(posixCodec.printPath(prefix))

    val stripRead: LogicalPlan ~> LogicalPlan =
      new (LogicalPlan ~> LogicalPlan) {
        def apply[A](lp: LogicalPlan[A]) = lp match {
          case ReadF(p) => ReadF(p.rebase(base).map(_.asAbsolute) | p)
          case _        => lp
        }
      }

    val stripPlan: Fix[LogicalPlan] => Fix[LogicalPlan] =
      _ translate stripRead

    fsPathError.modify(stripAPathPrefix(prefix)) compose
      fsPlannerError.modify(stripPlan)
  }

  private def stripNodePrefix(prefix: ADir): Node => Node =
    _.fold(
      Node.Mount compose stripPrefixR(prefix),
      Node.Plain compose stripRPathPrefix(prefix),
      Node.View  compose stripPrefixR(prefix))
}

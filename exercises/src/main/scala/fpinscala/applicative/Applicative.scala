package fpinscala
package applicative

import monads.Functor
import state._
import State._
import StateUtil._

// defined at bottom of this file
import monoids._
import language.higherKinds
import language.implicitConversions

trait Applicative[F[_]] extends Functor[F] {
  self =>

  def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C] =
    apply(map(fa)(f.curried): F[B => C])(fb)

  def apply[A, B](fab: F[A => B])(fa: F[A]): F[B] =
    map2(fab, fa)((ab, a) => ab(a))

  def unit[A](a: => A): F[A]

  def map[A, B](fa: F[A])(f: A => B): F[B] =
    apply(unit(f): F[A => B])(fa)

  def map3[A, B, C, D](fa: F[A], fb: F[B], fc: F[C])(f: (A, B, C) => D): F[D] =
    apply(map2(fa, fb)((a, b) => f.curried(a)(b)): F[C => D])(fc)

  def map4[A, B, C, D, E](fa: F[A], fb: F[B], fc: F[C], fd: F[D])(f: (A, B, C, D) => E): F[E] =
    apply(map3(fa, fb, fc)((a, b, c) => f.curried(a)(b)(c)): F[D => E])(fd)

  def sequence[A](fas: List[F[A]]): F[List[A]] =
    traverse(fas)(x => x)

  def traverse[A, B](as: List[A])(f: A => F[B]): F[List[B]] =
    as.foldRight(unit(List[B]()))((h, tm) => map2(f(h), tm)(_ :: _))

  def replicateM[A](n: Int, fa: F[A]): F[List[A]] =
    sequence((1 to n).toList.map(_ => fa))

  def factor[A, B](fa: F[A], fb: F[B]): F[(A, B)] =
    map2(fa, fb)((_, _))

  def product[G[_]](G: Applicative[G]): Applicative[({type f[x] = (F[x], G[x])})#f] =
    new Applicative[({type f[x] = (F[x], G[x])})#f] {
      override def unit[A](a: => A): (F[A], G[A]) = (self.unit(a), G.unit(a))

      override def map2[A, B, C](fa: (F[A], G[A]), fb: (F[B], G[B]))
                                (f: (A, B) => C): (F[C], G[C]) =
        (self.map2(fa._1, fb._1)(f), G.map2(fa._2, fb._2)(f))
    }

  def compose[G[_]](G: Applicative[G]): Applicative[({type f[x] = F[G[x]]})#f] =
    new Applicative[({type f[x] = F[G[x]]})#f] {
      override def unit[A](a: => A): F[G[A]] = self.unit(G.unit(a))

      override def apply[A, B](f: F[G[A => B]])(fa: F[G[A]]): F[G[B]] =
        self.map2(f, fa)((ff: G[A => B], ffa: G[A]) => G.apply(ff)(ffa))
    }

  def sequenceMap[K, V](ofa: Map[K, F[V]]): F[Map[K, V]] =
    ofa.foldRight(unit(Map[K, V]())) { case ((k, vf), mf) =>
      map2(vf, mf)((v: V, m: Map[K, V]) => m + (k -> v))
    }

}

case class Tree[+A](head: A, tail: List[Tree[A]])

trait Monad[F[_]] extends Applicative[F] {
  def flatMap[A, B](ma: F[A])(f: A => F[B]): F[B] = join(map(ma)(f))

  def join[A](mma: F[F[A]]): F[A] = flatMap(mma)(ma => ma)

  def compose[A, B, C](f: A => F[B], g: B => F[C]): A => F[C] =
    a => flatMap(f(a))(g)

  override def apply[A, B](mf: F[A => B])(ma: F[A]): F[B] =
    flatMap(mf)(f => map(ma)(a => f(a)))

  override def map[A, B](fa: F[A])(f: A => B): F[B] =
    flatMap(fa)(a => unit(f(a)))

  override def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C] =
    flatMap(fa)(a => map(fb)(b => f(a, b)))
}

object Monad {
  def eitherMonad[E]: Monad[({type f[x] = Either[E, x]})#f] =
    new Monad[({type f[x] = scala.Either[E, x]})#f] {
      override def unit[A](a: => A): Either[E, A] = Right(a)

      override def flatMap[A, B](am: Either[E, A])(f: A => Either[E, B]) = am match {
        case Left(e) => Left(e)
        case Right(a) => f(a)
      }
    }

  def stateMonad[S] = new Monad[({type f[x] = State[S, x]})#f] {
    def unit[A](a: => A): State[S, A] = State(s => (a, s))

    override def flatMap[A, B](st: State[S, A])(f: A => State[S, B]): State[S, B] =
      st flatMap f
  }

  def composeM[F[_], N[_]](implicit F: Monad[F], N: Monad[N], T: Traverse[N]):
  Monad[({type f[x] = F[N[x]]})#f] = new Monad[({type f[x] = F[N[x]]})#f] {
    override def unit[A](a: => A): F[N[A]] = F.unit(N.unit(a))

    override def flatMap[A, B](ma: F[N[A]])(f: A => F[N[B]]): F[N[B]] = ???

  }
}

sealed trait Validation[+E, +A]

case class Failure[E](head: E, tail: Vector[E])
  extends Validation[E, Nothing]

case class Success[A](a: A) extends Validation[Nothing, A]


object Applicative {

  val streamApplicative = new Applicative[Stream] {

    def unit[A](a: => A): Stream[A] =
      Stream.continually(a) // The infinite, constant stream

    override def map2[A, B, C](a: Stream[A], b: Stream[B])(// Combine elements pointwise
                                                           f: (A, B) => C): Stream[C] =
      a zip b map f.tupled
  }

  def validationApplicative[E]: Applicative[({type f[x] = Validation[E, x]})#f] =
    new Applicative[({type f[x] = _root_.fpinscala.applicative.Validation[E, x]})#f] {
      def unit[A](a: => A): Success[A] = Success(a)

      override def map2[A, B, C](av: Validation[E, A], bv: Validation[E, B])(f: (A, B) => C) = {
        (av, bv) match {
          case (Failure(h1, t1), Failure(h2, t2)) => Failure(h1, t1 ++ Vector(h2) ++ t2)
          case (Success(a), Success(b)) => Success(f(a, b))
          case (e@Failure(_, _), _) => e
          case (_, e@Failure(_, _)) => e

        }
      }
    }

  type Const[A, B] = A

  implicit def monoidApplicative[M](M: Monoid[M]) =
    new Applicative[({type f[x] = Const[M, x]})#f] {
      def unit[A](a: => A): M = M.zero

      override def apply[A, B](m1: M)(m2: M): M = M.op(m1, m2)
    }
}

trait Traverse[F[_]] extends Functor[F] with Foldable[F] {
  def traverse[G[_] : Applicative, A, B](fa: F[A])(f: A => G[B]): G[F[B]] =
    sequence(map(fa)(f))

  def sequence[G[_] : Applicative, A](fma: F[G[A]]): G[F[A]] =
    traverse(fma)(ma => ma)


  case class Id[A](get: A)

  val IdApplicative = new Applicative[Id] {
    def unit[A](a: => A) = Id(a)

    override def map2[A, B, C](fa: Id[A], fb: Id[B])(f: (A, B) => C): Id[C] =
      Id(f(fa.get, fb.get))
  }

  override def map[A, B](fa: F[A])(f: A => B): F[B] = {
    val x = (a: A) => IdApplicative.unit(f(a))
    traverse(fa)(x).get
  }

  import Applicative._
  import StateUtil._

  override def foldMap[A, B](as: F[A])(f: A => B)(mb: Monoid[B]): B =
    traverse[({type f[x] = Const[B, x]})#f, A, Nothing](as
    )(f)(monoidApplicative(mb))

  def traverseS[S, A, B](fa: F[A])(f: A => State[S, B]): State[S, F[B]] =
    traverse[({type f[x] = State[S, x]})#f, A, B](fa)(f)(Monad.stateMonad)

  def mapAccum[S, A, B](fa: F[A], s: S)(f: (A, S) => (B, S)): (F[B], S) =
    traverseS(fa)((a: A) => (for {
      s1 <- get[S]
      (b, s2) = f(a, s1)
      _ <- set(s2)
    } yield b)).run(s)

  override def toList[A](fa: F[A]): List[A] =
    mapAccum(fa, List[A]())((a, s) => ((), a :: s))._2.reverse

  def zipWithIndex[A](fa: F[A]): F[(A, Int)] =
    mapAccum(fa, 0)((a, s) => ((a, s), s + 1))._1

  def reverse1[A](fa: F[A]): F[A] = traverseS(fa)((a: A) => (for {
    s1 <- get[List[A]]
    _ <- set(s1.tail)
  } yield (s1.head))).run(toList(fa))._1

  def reverse[A](fa: F[A]): F[A] =
    mapAccum(fa, toList(fa))((a, s) => (s.head, s.tail))._1

  override def foldLeft[A, B](fa: F[A])(z: B)(f: (B, A) => B): B =
    mapAccum(fa, z)((a, s) => ((), f(s, a)))._2

  def fuse[G[_], H[_], A, B](fa: F[A])(f: A => G[B], g: A => H[B])
                            (implicit G: Applicative[G], H: Applicative[H]): (G[F[B]], H[F[B]]) =
    traverse[({type f[x] = (G[x], H[x])})#f, A, B](fa)(a => (f(a), g(a)))(G product H)

  def compose[G[_]](implicit G: Traverse[G]): Traverse[({type f[x] = F[G[x]]})#f] =
    new Traverse[({type f[x] = F[G[x]]})#f] {
      def traverse[H[_] : Applicative, A, B](fa: F[G[A]])(f: A => H[B]): H[F[G[B]]] =
        
    }
}

object Traverse {
  val listTraverse = new Traverse[List] {
    def traverse[G[_] : Applicative, A, B](fa: List[A])(f: A => G[B])
                                          (implicit G: Applicative[G]): G[List[B]] =
      fa.foldRight(G.unit(List[B]()))((a, lbA) => G.map2(f(a), lbA)(_ :: _))
  }

  val optionTraverse = new Traverse[Option] {
    def traverse[G[_] : Applicative, A, B](fa: Option[A])(f: A => G[B])(implicit G: Applicative[G]): G[Option[B]] =
      fa match {
        case Some(a) => G.map(f(a))(Some(_))
        case None => G.unit(None)
      }
  }

  val treeTraverse = new Traverse[Tree] {
    def traverse[G[_] : Applicative, A, B](fa: Tree[A])(f: A => G[B])(implicit G: Applicative[G]): G[Tree[B]] =
      G.map2(f(fa.head), G.sequence(fa.tail.map(t => traverse(t)(f))))(Tree(_, _))
  }
}

// The `get` and `set` functions on `State` are used above,
// but aren't in the `exercises` subproject, so we include
// them here
object StateUtil {

  def get[S]: State[S, S] =
    State(s => (s, s))

  def set[S](s: S): State[S, Unit] =
    State(_ => ((), s))
}

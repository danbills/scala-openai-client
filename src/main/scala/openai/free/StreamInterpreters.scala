package openai.free

import cats.~>
import fs2.Stream

/** Effect-agnostic pieces for interpreting into `Stream[F, *]`; no typeclass constraints on `F`. */
object StreamInterpreters:

  /** [[StreamControlOp.Halt]] is the empty stream. */
  def control[F[_]]: StreamControlOp ~> Stream[F, *] = new (StreamControlOp ~> Stream[F, *]):
    def apply[A](op: StreamControlOp[A]): Stream[F, A] = op match
      case StreamControlOp.Halt => Stream.empty

  /** Lift a single-shot interpreter into the stream target: each op becomes a one-element stream. */
  def lift[G[_], F[_]](fk: G ~> F): G ~> Stream[F, *] = new (G ~> Stream[F, *]):
    def apply[A](op: G[A]): Stream[F, A] = Stream.eval(fk(op))

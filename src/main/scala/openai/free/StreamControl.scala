package openai.free

import cats.InjectK
import cats.free.Free

/** Control for multi-valued interpretation: [[Halt]] yields no value, ending that branch (`Stream.empty`). */
enum StreamControlOp[A]:
  case Halt extends StreamControlOp[Nothing]

/** Smart constructor for [[StreamControlOp]]; shared by every streaming capability. */
final class StreamControl[G[_]](using InjectK[StreamControlOp, G]):
  def halt[A]: Free[G, A] = Free.liftInject[G](StreamControlOp.Halt).map(n => n)

object StreamControl:
  given [G[_]](using InjectK[StreamControlOp, G]): StreamControl[G] = new StreamControl[G]
  def apply[G[_]](using c: StreamControl[G]): StreamControl[G]      = c

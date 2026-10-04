package openai.demo

import cats.InjectK
import cats.data.EitherK
import cats.effect.std.Console
import cats.free.Free
import cats.~>
import openai.free.OpenAIDeviceOp

/** The demos' own algebra, mixed into the library coproducts: progress lines on the console. */
enum ConsoleOp[A]:
  case PrintLine(line: String) extends ConsoleOp[Unit]

final class DemoConsole[G[_]](using InjectK[ConsoleOp, G]):
  def println(line: String): Free[G, Unit] = Free.liftInject[G](ConsoleOp.PrintLine(line))

object DemoConsole:
  given [G[_]](using InjectK[ConsoleOp, G]): DemoConsole[G] = new DemoConsole[G]

  def interpreter[F[_]: Console]: ConsoleOp ~> F = new (ConsoleOp ~> F):
    def apply[A](op: ConsoleOp[A]): F[A] = op match
      case ConsoleOp.PrintLine(line) => Console[F].println(line)

/** Console + chat + audio endpoints + microphone/speaker. */
type DemoOp[A] = EitherK[ConsoleOp, OpenAIDeviceOp, A]

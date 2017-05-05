package com.criteo.cuttle.timeseries

import Internal._
import com.criteo.cuttle._

import scala.concurrent._
import scala.concurrent.duration.{Duration => ScalaDuration}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.stm._

import cats.implicits._

import algebra.lattice.Bool._

import io.circe._
import io.circe.generic.semiauto._

import doobie.imports._

import java.time._
import java.time.temporal.ChronoUnit._
import java.time.temporal._

import continuum.{Interval, IntervalSet}
import continuum.bound._

sealed trait TimeSeriesGrid

case object Hourly extends TimeSeriesGrid

case class Daily(tz: ZoneId) extends TimeSeriesGrid

private case object Continuous extends TimeSeriesGrid

case class Backfill(id: String,
                    start: LocalDateTime,
                    end: LocalDateTime,
                    jobs: Set[Job[TimeSeriesScheduling]],
                    priority: Int)
object Backfill {
  implicit val encoder: Encoder[Backfill] = deriveEncoder[Backfill]
  implicit def decoder(implicit jobs: Set[Job[TimeSeriesScheduling]]) =
    deriveDecoder[Backfill]
}

case class TimeSeriesContext(start: LocalDateTime, end: LocalDateTime, backfill: Option[Backfill] = None)
    extends SchedulingContext {
  import TimeSeriesUtils._

  def toJson = Json.obj(
    "start" -> Json.fromLong(start.toEpochSecond(ZoneOffset.of("Z"))),
    "end" -> Json.fromLong(end.toEpochSecond(ZoneOffset.of("Z")))
  )

  def toInterval: Interval[LocalDateTime] = Interval.closedOpen(start, end)
}

object TimeSeriesContext {
  import TimeSeriesUtils._

  implicit val ordering: Ordering[TimeSeriesContext] = {
    implicit val maybeBackfillOrdering: Ordering[Option[Backfill]] = {
      Ordering.by(maybeBackfill => maybeBackfill.map(_.priority).getOrElse(0))
    }
    Ordering.by(context => (context.backfill, context.start))
  }
}

case class TimeSeriesDependency(offset: Duration)

case class TimeSeriesScheduling(grid: TimeSeriesGrid, start: LocalDateTime, maxPeriods: Int = 1) extends Scheduling {
  type Context = TimeSeriesContext
  type DependencyDescriptor = TimeSeriesDependency
}

object TimeSeriesScheduling {
  implicit def scheduler = TimeSeriesScheduler()
}

case class TimeSeriesScheduler() extends Scheduler[TimeSeriesScheduling] with TimeSeriesApp {
  import TimeSeriesUtils._

  private val timer =
    Job("timer",
        None,
        None,
        Set(),
        TimeSeriesScheduling(Continuous, LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)))(_ => sys.error("panic!"))

  private val _state = Ref(Map.empty[TimeSeriesJob, IntervalSet[LocalDateTime]])
  private val _backfills = TSet.empty[Backfill]

  def state: (State, Set[Backfill]) = atomic { implicit txn =>
    (_state(), _backfills.snapshot)
  }

  def backfillJob(id: String, job: TimeSeriesJob, start: LocalDateTime, end: LocalDateTime, priority: Int) = atomic {
    implicit txn =>
      val newBackfill = Backfill(id, start, end, Set(job), priority)
      _backfills += newBackfill
      _state() = _state() + (job -> (_state().apply(job) - Interval.closedOpen(start, end)))
  }

  def backfillDomain(backfill: Backfill) =
    backfill.jobs.map(job => job -> IntervalSet(Interval.closedOpen(backfill.start, backfill.end))).toMap

  def run(graph: Graph[TimeSeriesScheduling], executor: Executor[TimeSeriesScheduling], xa: XA): Unit = {
    Database.doSchemaUpdates.transact(xa).unsafePerformIO

    Database
      .deserialize(graph.vertices)
      .transact(xa)
      .unsafePerformIO
      .foreach {
        case (state, backfillState) =>
          atomic { implicit txn =>
            _state() = _state() ++ state
            _backfills ++= backfillState
          }
      }

    atomic { implicit txn =>
      graph.vertices.foreach { job =>
        if (!_state().contains(job)) {
          _state() = _state() + (job -> IntervalSet.empty[LocalDateTime])
        }
      }
    }

    def addRuns(runs: Set[Run])(implicit txn: InTxn) =
      runs.foreach {
        case (job, context, _) =>
          _state() = _state() + (job -> (_state().apply(job) + context.toInterval))
      }

    def go(running: Set[Run]): Unit = {
      val (done, stillRunning) = running.partition(_._3.isCompleted)
      val (stateSnapshot, backfillSnapshot) = atomic { implicit txn =>
        addRuns(done)
        _backfills.retain { bf =>
          without(StateD(backfillDomain(bf)), StateD(_state())) =!= zero[StateD]
        }
        (_state(), _backfills.snapshot)
      }

      if (done.nonEmpty)
        Database.serialize(stateSnapshot, backfillSnapshot).transact(xa).unsafePerformIO

      val toRun = next(
        graph,
        stateSnapshot,
        backfillSnapshot,
        stillRunning.map { case (job, context, _) => (job, context) },
        IntervalSet(Interval.lessThan(LocalDateTime.parse("2017-05-06T01:00")))
      )
      val newRunning = stillRunning ++ toRun.map {
        case (job, context) =>
          (job, context, executor.run(job, context))
      }
      Future.firstCompletedOf(utils.Timeout(ScalaDuration.create(1, "s")) :: newRunning.map(_._3).toList).andThen {
        case _ => go(newRunning)
      }
    }

    go(Set.empty)
  }

  def split(start: LocalDateTime,
            end: LocalDateTime,
            tz: ZoneId,
            unit: ChronoUnit,
            maxPeriods: Int): Iterator[TimeSeriesContext] = {
    val List(zonedStart, zonedEnd) = List(start, end).map { t =>
      t.atZone(UTC).withZoneSameInstant(tz)
    }

    val truncatedStart = zonedStart.truncatedTo(unit)
    val alignedStart =
      if (truncatedStart == zonedStart)
        zonedStart
      else
        truncatedStart.plus(1, unit)

    val alignedEnd = zonedEnd.truncatedTo(unit)
    val periods = alignedStart.until(alignedEnd, unit)

    (0L to (periods - 1)).grouped(maxPeriods).map { l =>
      def alignedNth(k: Long) =
        alignedStart
          .plus(k, unit)
          .withZoneSameInstant(UTC)
          .toLocalDateTime

      TimeSeriesContext(alignedNth(l.head), alignedNth(l.last + 1))
    }
  }

  def next(graph0: Graph[TimeSeriesScheduling],
           state0: State,
           backfills: Set[Backfill],
           running: Set[(TimeSeriesJob, TimeSeriesContext)],
           timerInterval: IntervalSet[LocalDateTime]): List[Executable] = {
    val graph = graph0 dependsOn timer
    val state = state0 + (timer -> timerInterval)

    val runningIntervals = StateD {
      running
        .groupBy(_._1)
        .mapValues(_.map(x => x._2.toInterval)
          .foldLeft(IntervalSet.empty[LocalDateTime])((is, interval) => is + interval))
        .toMap
    }

    val dependencies = StateD {
      (for {
        (child, parent, TimeSeriesDependency(offset)) <- graph.edges
        is <- state.get(parent).toList
        interval <- is
      } yield (child -> interval.map(_.plus(offset))))
        .groupBy(_._1)
        .mapValues(x => IntervalSet(x.toList.map(_._2): _*))
    }

    val jobDomain = StateD(graph.vertices.map(job => job -> IntervalSet(Interval.atLeast(job.scheduling.start))).toMap)

    val ready = Seq(complement(runningIntervals), complement(StateD(state)), jobDomain, dependencies)
      .reduce(and(_, _))

    val toBackfill: Map[Backfill, StateD] =
      backfills.map { backfill =>
        backfill -> and(ready, StateD(backfillDomain(backfill)))
      }.toMap

    val toRunNormally = without(ready, toBackfill.values.fold(zero[StateD])(or(_, _)))

    val toRun: Map[Option[Backfill], State] =
      toBackfill.map {
        case (backfill, StateD(st, _)) =>
          (Some(backfill): Option[Backfill]) -> st
      } + (None -> toRunNormally.defined)

    val splitInterval = (job: TimeSeriesJob, interval: Interval[LocalDateTime]) => {
      val (unit, tz) = job.scheduling.grid match {
        case Hourly => (HOURS, UTC)
        case Daily(_tz) => (DAYS, _tz)
        case Continuous => sys.error("panic!")
      }
      val Closed(start) = interval.lower.bound
      val Open(end) = interval.upper.bound
      split(start, end, tz, unit, job.scheduling.maxPeriods)
    }

    for {
      (maybeBackfill, state) <- toRun.toList
      (job, intervalSet) <- state.toList.filterNot { case (job, _) => job == timer }
      interval <- intervalSet
      context <- splitInterval(job, interval)
    } yield (job, context.copy(backfill = maybeBackfill))
  }
}

object TimeSeriesUtils {
  type TimeSeriesJob = Job[TimeSeriesScheduling]
  type State = Map[TimeSeriesJob, IntervalSet[LocalDateTime]]
  type Executable = (TimeSeriesJob, TimeSeriesContext)
  type Run = (TimeSeriesJob, TimeSeriesContext, Future[Unit])

  val UTC: ZoneId = ZoneId.of("UTC")

  val emptyIntervalSet: IntervalSet[LocalDateTime] = IntervalSet.empty[LocalDateTime]

  implicit val dateTimeOrdering: Ordering[LocalDateTime] =
    Ordering.fromLessThan((t1: LocalDateTime, t2: LocalDateTime) => t1.isBefore(t2))
}
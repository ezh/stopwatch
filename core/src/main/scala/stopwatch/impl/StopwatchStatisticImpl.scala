/*
 *  Copyright 2009-2010 Alex Boisvert
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stopwatch.impl

import stopwatch.StopwatchGroup
import stopwatch.StopwatchRange
import stopwatch.StopwatchStatistic

/**
 * Time statistics for a stopwatch.
 */
final class StopwatchStatisticImpl(val group: StopwatchGroup, val name: String, 
                                   val movingAverage: Option[CumulativeMovingAverageImpl])
  extends StopwatchStatistic
  with Cloneable
{
  private val millis = 1000000 // used for nanos => milliseconds conversion

  /** Whether the stopwatch is enabled */
  @volatile var enabled: Boolean = true // enabled by default

  /** Number of start "hits" */
  private var _hits: Long = 0

  /** Number of errors */
  private var _errors: Long = 0

  /** Total time, represents the elapsed time while the stopwatch was started. */
  private var _totalTime: Long = 0

  /** Minimum time spent in the stopwatch. */
  private var _minTime = -1L

  /** Maximum time spent in the stopwatch. */
  private var _maxTime = -1L

  /** Average time spent in the stopwatch. */
  private var _averageTime: Long = 0

  /** Standard deviation of time spent in the stopwatch. */
  private var _standardDeviationTime: Long = 0

  /** Maximum number of threads operating on this stopwatch at once. */
  private var _maxThreads: Int = 0

  /** Number of threads currently operating on this stopwatch. */
  private var _currentThreads: Int = 0

  /** Average number of threads when stopwatch is entered. */
  private var _averageThreads: Float = 0

  /** Time when stopwatch was first accessed. */
  private var _firstAccessTime: Option[Long] = None

  /** Time when stopwatch was last accessed. */
  private var _lastAccessTime: Option[Long] = None

  /**
   * Hit distribution per interval, as defined by range
   */
  private var _distribution: Array[Long] = null

  /** Number of hits under range. */
  private var _hitsUnderRange = 0L

  /** Number of hits over range. */
  private var _hitsOverRange = 0L

  /** Used to calculate the average number of threads */
  private var _totalThreadsDuringStart: Long = 0

  /** Used to calculate the standard deviation. */
  private var _sumOfSquares: Double = 0.0d

  /** Reference to group's range object to track change. */
  private var _range: StopwatchRange = null

  /* initialization */
  {
    updateRange
  }

  def hits = _hits

  def errors = _errors

  def range = group.range

  def totalTime = _totalTime

  def minTime = _minTime

  def maxTime = _maxTime

  def averageTime = _averageTime

  def standardDeviationTime = _standardDeviationTime

  def maxThreads = _maxThreads

  def currentThreads = _currentThreads

  def averageThreads = _averageThreads

  def firstAccessTime = _firstAccessTime

  def lastAccessTime = _lastAccessTime

  def distribution: Seq[Long] = {
    if (range eq null) return Nil
    if (_distribution eq null) List.make(range.intervals, 0) else _distribution
  }

  def hitsUnderRange = _hitsUnderRange

  def hitsOverRange = _hitsOverRange

  /**
   * Update statistics following stopwatch start event.
   */
  private[impl] def notifyStart(currentTime: Long) = synchronized {
    _currentThreads += 1
    _hits += 1
    _totalThreadsDuringStart += _currentThreads
    _lastAccessTime = Some(currentTime)
    if (_currentThreads > _maxThreads) _maxThreads = _currentThreads
    if (_hits == 1) _firstAccessTime = Some(currentTime)
  }

  /**
   * Update statistics following stopwatch stop event.
   */
  private[impl] def notifyStop(currentTime: Long, elapsed: Long, error: Boolean) = {
    synchronized {
      _currentThreads -= 1
      _totalTime += elapsed
      _lastAccessTime = Some(currentTime)
      _sumOfSquares += elapsed*elapsed

      if (error) _errors += 1

      if (_maxTime < elapsed) _maxTime = elapsed
      if ((_minTime > elapsed) || (_minTime == -1L)) _minTime = elapsed

      updateRange

      if (!(range eq null)) {
        val interval = range.interval(elapsed)
        if (interval < 0) _hitsUnderRange += 1
        else if (interval >= range.intervals) _hitsOverRange += 1
        else _distribution(interval) += 1
      }
    }
    group.notifyListeners(this)
  }

  private[stopwatch] def notifyPeriodChange = synchronized {
    movingAverage.foreach { avg => avg ::= snapshot }
  }

  // override StopwatchStatistic.reset()
  def reset() = synchronized {
    _hits = 0
    _errors = 0
    _totalTime = 0
    _minTime = -1
    _maxTime = -1
    _firstAccessTime = None
    _lastAccessTime  = None
    _maxThreads = 0
    _range = null
    _distribution = null
    _hitsUnderRange = 0
    _hitsOverRange = 0
    _totalThreadsDuringStart = 0
    _sumOfSquares = 0
  }

  /** Take a snapshot of the StopwatchStatistic. */
  def snapshot() = {
    // clone() is synchronized -- see below
    val snapshot = clone().asInstanceOf[StopwatchStatisticImpl]

    // calculate average threads & average time
    if (snapshot._hits == 0) {
      snapshot._averageThreads = 0.0f
      snapshot._averageTime = 0L
    } else {
      snapshot._averageThreads = snapshot._totalThreadsDuringStart / snapshot._hits
      snapshot._averageTime = snapshot._totalTime / snapshot._hits
    }

    /* Calculate standard deviation:
            hits - count of all x's
            numerator = sum(xi^2) - sum(xi)^2/n
            std_dev = square_root(numerator / (n-1))
     */
    snapshot._standardDeviationTime = 0
    if (snapshot._hits != 0) {
      val sumOfX: Double = snapshot._totalTime
      val n = snapshot._hits
      val nMinus1: Double = (n-1) max 1
      val numerator: Double = snapshot._sumOfSquares - ((sumOfX * sumOfX) / n)
      snapshot._standardDeviationTime = java.lang.Math.sqrt(numerator.toDouble / nMinus1).toLong
    }
    snapshot
  }

  private def updateRange() {
    val range = group.range
    if (!(_range eq range)) {
      _range = range
      if (range == null) _distribution = null
      else _distribution = new Array[Long](range.intervals)
      _hitsUnderRange = 0
      _hitsOverRange = 0
    }
  }

  override def clone() = synchronized {
    val cloned = super.clone().asInstanceOf[StopwatchStatisticImpl]

    // the default clone of an array is shallow.
    if (_distribution != null) {
      cloned._distribution = _distribution.toArray // make a copy
    }
    cloned
  }

  override def hashCode = name.hashCode

  override def equals(other: Any): Boolean = other match {
    case x: StopwatchStatistic => name == x.name
    case _ => false
  }

  override def toString() = toMediumString

  /** Returns a short string representation of stopwatch statistics */
  def toShortString = {
    "Stopwatch \"%s\" {hits=%d, errors=%d, min=%dms, avg=%dms, max=%dms, total=%dms, stdDev=%dms}".
      format(name, _hits, _errors, _minTime/millis, _averageTime/millis, _maxTime/millis,
             _totalTime/millis, _standardDeviationTime/millis)
  }

  /** Returns a medium-length string representation of stopwatch statistics */
  def toMediumString = {
    val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z")
    val delta = for {
      last <- _lastAccessTime
      first <-_firstAccessTime
    } yield last-first

    val throughput = for (d <- delta) yield (_hits * 1000 / (d: Float))

    def formatTime(time: Option[Long]) = time.map(dateFormat.format(_)) getOrElse "N/A"

    ("Stopwatch \"%s\" {hits=%d, throughput=%.3f/s, errors=%d," +
     "minTime=%dms, avgTime=%dms, maxTime=%dms, totalTime=%dms, stdDev=%dms, " +
     "currentThreads=%d, avgThreads=%.2f, maxThreads=%d, " +
     "first=%s, last=%s}" ).format(
      name, _hits, throughput getOrElse -1L, _errors,
      _minTime/millis, _averageTime/millis, _maxTime/millis, _totalTime/millis, _standardDeviationTime/millis,
      _currentThreads, _averageThreads, _maxThreads,
      formatTime(firstAccessTime), formatTime(_lastAccessTime))
  }

  /** Returns a long string representation of stopwatch statistics,
   *  including time distribution.
   */
  def toLongString = {
    toMediumString + (if (_range eq null) "" else {
      _range.intervalsAsTuple.map( { case (lower, upper) =>
         (lower/millis)+"-"+(upper/millis)+"ms: "+_distribution(_range.interval(lower)) }
       ).mkString(" Distribution {under=%d, ", ", ", ", over=%d}").
         format(_hitsUnderRange, _hitsOverRange)
    })
  }
}

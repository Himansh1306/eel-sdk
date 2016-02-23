package io.eels

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import com.sksamuel.scalax.collection.ConcurrentLinkedQueueConcurrentIterator
import io.eels.plan._

import scala.concurrent.ExecutionContext

trait Frame {
  outer =>

  val DefaultBufferSize = 1000

  def schema: FrameSchema

  def buffer: Buffer

  def join(other: Frame): Frame = new Frame {
    override def schema: FrameSchema = outer.schema.join(other.schema)
    override def buffer: Buffer = new Buffer {

      val buffer1 = outer.buffer
      val buffer2 = other.buffer

      override def close(): Unit = {
        buffer1.close()
        buffer2.close()
      }

      override def iterator: Iterator[InternalRow] = new Iterator[InternalRow] {
        val iter1 = buffer1.iterator
        val iter2 = buffer2.iterator
        override def hasNext: Boolean = iter1.hasNext && iter2.hasNext
        override def next(): InternalRow = iter1.next() ++ iter2.next()
      }
    }
  }

  def replace(from: String, target: Any): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map(RowUtils.replace(from, target, _))
    }
  }

  def replace(columnName: String, from: String, target: Any): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val index = outer.schema.indexOf(columnName)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map(RowUtils.replace(index, from, target, _))
    }
  }

  def replace(columnName: String, fn: Any => Any): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val index = outer.schema.indexOf(columnName)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map(RowUtils.replaceByFn(index, fn, _))
    }
  }

  def takeWhile(pred: InternalRow => Boolean): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.takeWhile(pred)
    }
  }

  def takeWhile(columnName: String, p: Any => Boolean): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val index = outer.schema.indexOf(columnName)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.takeWhile(row => p(row(index)))
    }
  }

  def except(other: Frame): Frame = new Frame {

    override def schema: FrameSchema = outer.schema.removeColumns(other.schema.columnNames)

    override def buffer: Buffer = new Buffer {
      val buffer1 = outer.buffer
      val buffer2 = other.buffer
      val schema1 = outer.schema
      val schema2 = other.schema

      override def close(): Unit = {
        buffer1.close()
        buffer2.close()
      }

      override def iterator: Iterator[InternalRow] = new Iterator[InternalRow] {
        val iter1 = buffer1.iterator
        val iter2 = buffer2.iterator
        override def hasNext: Boolean = iter1.hasNext && iter2.hasNext
        override def next(): InternalRow = RowUtils.toMap(schema1, iter1.next).--(schema2.columnNames).values.toList
      }
    }
  }

  def dropWhile(p: InternalRow => Boolean): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.dropWhile(p)
    }
  }

  def dropWhile(columnName: String, p: Any => Boolean): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val index = outer.schema.indexOf(columnName)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.dropWhile(row => p(row(index)))
    }
  }

  /**
    * Returns a new Frame where only each "k" row is retained. Ie, if sample is 2, then on average,
    * every other row will be returned. If sample is 10 then only 10% of rows will be returned.
    * When running concurrently, the rows that are sampled will vary depending on the ordering that the
    * workers pull through the rows. Each iterator (thread) uses its own count for the sample.
    */
  def sample(k: Int): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.grouped(k).map(_.head)
    }
  }

  def addColumn(name: String, defaultValue: Any): Frame = new Frame {
    require(!outer.schema.columnNames.contains(name), s"Column $name already exists")
    override lazy val schema: FrameSchema = outer.schema.addColumn(name)
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map(_ :+ defaultValue)
    }
  }

  def removeColumn(columnName: String): Frame = new Frame {
    override lazy val schema: FrameSchema = outer.schema.removeColumn(columnName)
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val index = outer.schema.indexOf(columnName)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map { row =>
        row.slice(0, index) ++ row.slice(index + 1, row.length)
      }
    }
  }

  def updateColumn(column: Column): Frame = new Frame {
    override lazy val schema: FrameSchema = outer.schema.updateColumn(column)
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val index = outer.schema.indexOf(column)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator
    }
  }

  def renameColumn(nameFrom: String, nameTo: String): Frame = new Frame {
    override lazy val schema: FrameSchema = outer.schema.renameColumn(nameFrom, nameTo)
    override def buffer: Buffer = outer.buffer
  }

  def explode(f: InternalRow => Seq[InternalRow]): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.flatMap(f)
    }
  }

  def fill(defaultValue: String): Frame = new Frame {
    override lazy val schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map(_.map {
        case null => defaultValue
        case other => other
      })
    }
  }

  def ++(frame: Frame): Frame = union(frame)
  def union(other: Frame): Frame = new Frame {
    override lazy val schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer1 = outer.buffer
      val buffer2 = other.buffer
      override def close(): Unit = {
        buffer1.close()
        buffer2.close()
      }
      override def iterator: Iterator[InternalRow] = buffer1.iterator ++ buffer2.iterator
    }
  }

  def projectionExpression(expr: String): Frame = projection(expr.split(',').map(_.trim))
  def projection(first: String, rest: String*): Frame = projection(first +: rest)
  def projection(columns: Seq[String]): Frame = new Frame {

    lazy val outerSchema = outer.schema

    override lazy val schema: FrameSchema = {
      val newColumns = columns.map { col =>
        outerSchema.columns.find(_.name == col).getOrElse(sys.error(s"$col is not in the source frame"))
      }
      FrameSchema(newColumns.toList)
    }

    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      // we get a sequence of the indexes of the columns in the original schema, so we can just
      // apply those indexes to the incoming rows
      val indexes = columns.map(outerSchema.indexOf).toVector
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map(row => indexes.map(row.apply))
    }
  }

  /**
    * Execute a side effect function for every row in the frame, returning the same Frame.
    *
    * @param f the function to execute
    * @return this frame, to allow for builder style chaining
    */
  def foreach[U](f: (InternalRow) => U): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map {
        row =>
          f(row)
          row
      }
    }
  }

  def collect(pf: PartialFunction[InternalRow, InternalRow]): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.collect(pf)
    }
  }

  def drop(k: Int): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val count = new AtomicInteger(0)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.dropWhile(_ => count.getAndIncrement < k)
    }
  }

  def map(f: InternalRow => InternalRow): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.map(f)
    }
  }

  def filterNot(p: InternalRow => Boolean): Frame = filter(str => !p(str))

  def filter(p: InternalRow => Boolean): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.filter(p)
    }
  }

  /**
    * Filters where the given column matches the given predicate.
    */
  def filter(columnName: String, p: Any => Boolean): Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      val index = outer.schema.indexOf(columnName)
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.filter(row => p(row(index)))
    }
  }

  def dropNullRows: Frame = new Frame {
    override def schema: FrameSchema = outer.schema
    override def buffer: Buffer = new Buffer {
      val buffer = outer.buffer
      override def close(): Unit = buffer.close()
      override def iterator: Iterator[InternalRow] = buffer.iterator.filterNot(_.contains(null))
    }
  }

  // -- actions --
  def fold[A](a: A)(fn: (A, InternalRow) => A): A = FoldPlan(this, a)(fn)
  def forall(p: (InternalRow) => Boolean): Boolean = ForallPlan(this, p)
  def exists(p: (InternalRow) => Boolean): Boolean = ExistsPlan(this, p)
  def find(p: (InternalRow) => Boolean): Option[InternalRow] = FindPlan(this, p)
  def head: Option[InternalRow] = HeadPlan(this)

  def to(sink: Sink)(implicit executor: ExecutionContext): Long = SinkPlan(sink, this)
  def size(implicit executor: ExecutionContext): Long = ToSizePlan(this)
  def toSeq(implicit executor: ExecutionContext): Seq[Row] = ToSeqPlan(this)
  def toSet(implicit executor: ExecutionContext): scala.collection.mutable.Set[Row] = ToSetPlan(this)
}

object Frame {

  import scala.collection.JavaConverters._

  def apply(_schema: FrameSchema, first: InternalRow, rest: InternalRow*): Frame = apply(_schema, first +: rest)
  def apply(_schema: FrameSchema, rows: Seq[InternalRow]): Frame = new Frame {
    override def buffer: Buffer = new Buffer {
      val queue = new ConcurrentLinkedQueue[InternalRow](rows.asJava)
      override def close(): Unit = ()
      override def iterator: Iterator[InternalRow] = ConcurrentLinkedQueueConcurrentIterator(queue)
    }
    override def schema: FrameSchema = _schema
  }

  def apply(first: Map[String, String], rest: Map[String, String]*): Frame = new Frame {
    override lazy val schema: FrameSchema = FrameSchema(first.keys.map(Column.apply).toList)
    override def buffer: Buffer = new Buffer {
      val queue = new ConcurrentLinkedQueue[InternalRow]((first +: rest).map(_.valuesIterator.toSeq).asJava)
      override def close(): Unit = ()
      override def iterator: Iterator[InternalRow] = ConcurrentLinkedQueueConcurrentIterator(queue)
    }
  }
}
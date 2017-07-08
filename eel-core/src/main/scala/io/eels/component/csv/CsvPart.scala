package io.eels.component.csv

import com.sksamuel.exts.{Logging, TryOrLog}
import com.univocity.parsers.csv.CsvParser
import io.eels.datastream.Subscriber
import io.eels.schema.StructType
import io.eels.{Part, Row}
import org.apache.hadoop.fs.{FileSystem, Path}

class CsvPart(val createParser: () => CsvParser,
              val path: Path,
              val header: Header,
              val skipBadRows: Boolean,
              val schema: StructType)
             (implicit fs: FileSystem) extends Part with Logging {

  val rowsToSkip: Int = header match {
    case Header.FirstRow => 1
    case _ => 0
  }

  override def subscribe(subscriber: Subscriber[Seq[Row]]): Unit = {
    TryOrLog {
      val parser = createParser()
      val input = fs.open(path)

      try {
        parser.beginParsing(input)

        val iterator = Iterator.continually(parser.parseNext).takeWhile(_ != null).drop(rowsToSkip).map { records =>
          Row(schema, records.toVector)
        }

        iterator.grouped(1000).foreach(subscriber.next)
        subscriber.completed()

      } catch {
        case t: Throwable => subscriber.error(t)
      } finally {
        parser.stopParsing()
        input.close()
      }
    }
  }
}
package prism.codegen

import scala.collection.mutable._

class Row(implicit csv:CSVPrinter) {
  val cell:Map[String, Any] = Map.empty

  def += (e:(String, Any)) = {
    val (k,v) = e
    if (!csv.header.contains(k)) csv.header += k
    cell += k -> v
  }
}

trait CSVPrinter extends Printer {
  implicit val csv:CSVPrinter = this

  val header = ListBuffer[String]()
  val rows = ListBuffer[Row]()

  def addRow:Row = {
    val row = new Row
    rows += row
    row
  }

  def emit(row:Row) = {
    emitln(s"${header.map( h => row.cell.getOrElse(h, "") ).mkString(",")}")
  }

  def emitFile = {
    //TODO
    //if (!append || fileEmpty) {
      //emitln(header.mkString(","))
    //}
    rows.foreach(emit)
  }
}
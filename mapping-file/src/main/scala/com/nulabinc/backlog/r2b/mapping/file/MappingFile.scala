package com.nulabinc.backlog.r2b.mapping.file

import com.nulabinc.backlog.migration.common.utils.{IOUtil, Logging}
import com.nulabinc.backlog.r2b.mapping.domain.MappingJsonProtocol._
import com.nulabinc.backlog.r2b.mapping.domain.{Mapping, MappingsWrapper}
import spray.json.{JsonParser, _}

import scala.collection.mutable.ArrayBuffer
import scalax.file.Path

/**
  * @author uchida
  */
trait MappingFile extends Logging {

  def matchItem(redmine: MappingItem): String

  def backlogs: Seq[MappingItem]

  def redmines: Seq[MappingItem]

  def filePath: String

  def itemName: String

  def description: String

  def isDisplayDetail: Boolean

  def isValid: Boolean = errors.isEmpty

  def isExists: Boolean = {
    val path: Path = Path.fromString(filePath)
    path.isFile
  }

  def isParsed: Boolean = unmarshal().isDefined

  def create(afterMessage: () => Unit) = {
    IOUtil.output(Path.fromString(filePath), MappingsWrapper(description, redmines.map(convert)).toJson.prettyPrint)
    if (redmines.nonEmpty) {
      afterMessage()
    }
  }

  def nonEmpty(): Boolean = {
    unmarshal() match {
      case Some(mappings) => mappings.nonEmpty
      case _              => false
    }
  }

  def merge(): Seq[Mapping] = {
    unmarshal() match {
      case Some(currentItems) =>
        val mergeList: ArrayBuffer[Mapping] = ArrayBuffer()
        val addedList: ArrayBuffer[Mapping] = ArrayBuffer()
        redmines.foreach { redmineItem =>
          val optCurrentItem = currentItems.find(_.redmine == redmineItem.name)
          optCurrentItem match {
            case Some(currentItem) => mergeList += currentItem
            case _ =>
              mergeList += convert(redmineItem)
              addedList += convert(redmineItem)
          }
        }
        IOUtil.output(Path.fromString(filePath), MappingsWrapper(description, mergeList).toJson.prettyPrint)
        addedList
      case _ =>
        Seq.empty[Mapping]
    }
  }

  def unmarshal(): Option[Seq[Mapping]] = {
    val path: Path = Path.fromString(filePath)
    val json       = path.lines().mkString
    try {
      val wrapper: MappingsWrapper = JsonParser(json).convertTo[MappingsWrapper]
      Some(wrapper.mappings)
    } catch {
      case e: Throwable =>
        logger.error(e.getMessage, e)
        None
    }
  }

  def tryUnmarshal(): Seq[Mapping] = {
    val path = Path.fromString(filePath)
    val json = path.lines().mkString
    JsonParser(json).convertTo[MappingsWrapper].mappings
  }

  def errors: Seq[String] = {
    val fileName  = Path.fromString(filePath).name
    val validator = new MappingValidator(redmines, backlogs, itemName, fileName)
    validator.validate(unmarshal())
  }

  def display(name: String, mappingItems: Seq[MappingItem]): String =
    mappingItems.find(_.name == name) match {
      case Some(mappingItem) =>
        if (isDisplayDetail) s"${mappingItem.display}(${mappingItem.name})"
        else name
      case _ => name
    }

  private[this] def convert(redmine: MappingItem): Mapping = Mapping(redmine.name, matchItem(redmine))

}
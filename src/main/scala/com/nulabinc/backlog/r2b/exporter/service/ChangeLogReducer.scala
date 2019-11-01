package com.nulabinc.backlog.r2b.exporter.service

import java.io.{FileOutputStream, InputStream}
import java.net.URL
import java.nio.channels.Channels

import com.nulabinc.backlog.migration.common.conf.BacklogConstantValue
import com.nulabinc.backlog.migration.common.domain.{BacklogChangeLog, BacklogComment, BacklogIssue}
import com.nulabinc.backlog.migration.common.utils.{FileUtil, IOUtil, Logging, StringUtil}
import com.nulabinc.backlog.r2b.exporter.core.ExportContext
import com.osinka.i18n.Messages
import com.taskadapter.redmineapi.bean.Attachment

import better.files.{File => Path}

private[exporter] case class ReducedChangeLogWithMessage(optChangeLog: Option[BacklogChangeLog], message: String)

private[exporter] object ReducedChangeLogWithMessage {
  def createMessageOnly(message: String): ReducedChangeLogWithMessage =
    ReducedChangeLogWithMessage(None, s"$message\n")
  def createWithChangeLog(changeLog: BacklogChangeLog): ReducedChangeLogWithMessage =
    ReducedChangeLogWithMessage(Some(changeLog), "")
}

/**
 * @author uchida
  */
private[exporter] class ChangeLogReducer(exportContext: ExportContext,
                                         issueDirPath: Path,
                                         issue: BacklogIssue,
                                         comments: Seq[BacklogComment],
                                         attachments: Seq[Attachment]) extends Logging {

  def reduce(targetComment: BacklogComment, changeLog: BacklogChangeLog): ReducedChangeLogWithMessage =
    changeLog.field match {
      case BacklogConstantValue.ChangeLog.ATTACHMENT =>
        ReducedChangeLogWithMessage(AttachmentReducer.reduce(changeLog), "")
      case "done_ratio" =>
        val message = Messages(
          "common.change_comment",
          Messages("common.done_ratio"),
          getValue(changeLog.optOriginalValue),
          getValue(changeLog.optNewValue)
        )
        ReducedChangeLogWithMessage.createMessageOnly(s"$message\n")
      case "relates" =>
        val message = Messages(
          "common.change_comment",
          Messages("common.relation"),
          getValue(changeLog.optOriginalValue),
          getValue(changeLog.optNewValue)
        )
        ReducedChangeLogWithMessage.createMessageOnly(s"$message\n")
      case "is_private" =>
        val message = Messages(
          "common.change_comment",
          Messages("common.private"),
          getValue(privateValue(changeLog.optOriginalValue)),
          getValue(privateValue(changeLog.optNewValue))
        )
        ReducedChangeLogWithMessage.createMessageOnly(s"$message\n")
      case "project_id" =>
        val message = Messages(
          "common.change_comment",
          Messages("common.project"),
          getProjectName(changeLog.optOriginalValue),
          getProjectName(changeLog.optNewValue)
        )
        ReducedChangeLogWithMessage.createMessageOnly(s"$message\n")
      case BacklogConstantValue.ChangeLog.PARENT_ISSUE =>
        val optOriginal = changeLog.optOriginalValue
        val optNew = changeLog.optNewValue

        val result = (optOriginal, optNew) match {
          case (Some(originalId), Some(newId)) =>
            for {
              _ <- getParentIssueId(originalId)
              _ <- getParentIssueId(newId)
            } yield ()
          case (Some(originalId), None) =>
            for {
              _ <- getParentIssueId(originalId)
            } yield ()
          case (None, Some(newId)) =>
            for {
              _ <- getParentIssueId(newId)
            } yield ()
          case (None, None) =>
            Right(())
        }

        result match {
          case Right(_) =>
            ReducedChangeLogWithMessage.createWithChangeLog(changeLog.copy(optNewValue = ValueReducer.reduce(targetComment, changeLog)))
          case Left(error) =>
            logger.warn(s"Non Fatal. Reduce change log failed. Message: ${error.getMessage}")
            val oldValue = optOriginal.map(_ => "(deleted)")
            val newValue = optNew.map(_ => "(deleted)")
            val message = Messages(
              "common.change_comment",
              Messages("common.parent_issue"),
              getValue(oldValue),
              getValue(newValue)
            )
            ReducedChangeLogWithMessage(None, s"$message\n")
        }
      case _ =>
        ReducedChangeLogWithMessage.createWithChangeLog(changeLog.copy(optNewValue = ValueReducer.reduce(targetComment, changeLog)))
    }

  private[this] def getParentIssueId(strId: String): Either[Throwable, Int] =
    for {
      id <- StringUtil.safeStringToInt(strId).map(Right(_)).getOrElse(Left(new RuntimeException(s"cannot parse id. Input: $strId")))
      parentId <- exportContext.issueService.tryIssueOfId(id).map(_.getId)
      result <- if (parentId > 0) Right(parentId) else Left(new RuntimeException(s"invalid parent id: Input: $parentId"))
    } yield result

  private[this] def generateBacklogIssueKey(issueId: String): String =
    s"${exportContext.backlogProjectKey}-$issueId"

  private[this] def getValue(optValue: Option[String]): String =
    optValue.getOrElse(Messages("common.empty"))

  private[this] def getProjectName(optValue: Option[String]): String =
    optValue match {
      case Some(value) =>
        StringUtil.safeStringToInt(value) match {
          case Some(intValue) => exportContext.projectService.optProjectOfId(intValue).map(_.getName).getOrElse(Messages("common.empty"))
          case _              => Messages("common.empty")
        }
      case _ => Messages("common.empty")
    }

  private[this] def privateValue(optValue: Option[String]): Option[String] =
    optValue.map {
      case "0" => Messages("common.no")
      case "1" => Messages("common.yes")
    }

  object AttachmentReducer {
    def reduce(changeLog: BacklogChangeLog): Option[BacklogChangeLog] = {
      changeLog.optAttachmentInfo match {
        case Some(attachmentInfo) =>
          val optAttachment = attachments.find(attachment => FileUtil.normalize(attachment.getFileName) == attachmentInfo.name)
          optAttachment match {
            case Some(attachment) =>
              val url: URL = new URL(s"${attachment.getContentURL}?key=${exportContext.apiConfig.key}")
              download(attachmentInfo.name, url.openStream())
              Some(changeLog)
            case _ => None
          }
        case _ => Some(changeLog)
      }
    }

    private[this] def download(name: String, content: InputStream) = {
      val dir  = exportContext.backlogPaths.issueAttachmentDirectoryPath(issueDirPath)
      val path = exportContext.backlogPaths.issueAttachmentPath(dir, name)
      IOUtil.createDirectory(dir)
      val rbc = Channels.newChannel(content)
      val fos = new FileOutputStream(path.path.toFile)
      fos.getChannel.transferFrom(rbc, 0, java.lang.Long.MAX_VALUE)

      rbc.close()
      fos.close()
    }
  }

  object ValueReducer {
    def reduce(targetComment: BacklogComment, changeLog: BacklogChangeLog): Option[String] = {
      changeLog.field match {
        case BacklogConstantValue.ChangeLog.VERSION | BacklogConstantValue.ChangeLog.MILESTONE | BacklogConstantValue.ChangeLog.COMPONENT |
            BacklogConstantValue.ChangeLog.ISSUE_TYPE =>
          findProperty(comments)(changeLog.field) match {
            case Some(lastComment) if lastComment.optCreated == targetComment.optCreated =>
              changeLog.field match {
                case BacklogConstantValue.ChangeLog.VERSION =>
                  val issueValue = issue.versionNames.mkString(", ")
                  if (issueValue.trim.isEmpty) changeLog.optNewValue else Some(issueValue)
                case BacklogConstantValue.ChangeLog.MILESTONE =>
                  val issueValue = issue.milestoneNames.mkString(", ")
                  if (issueValue.trim.isEmpty) changeLog.optNewValue else Some(issueValue)
                case BacklogConstantValue.ChangeLog.COMPONENT =>
                  val issueValue = issue.categoryNames.mkString(", ")
                  if (issueValue.trim.isEmpty) changeLog.optNewValue else Some(issueValue)
                case BacklogConstantValue.ChangeLog.ISSUE_TYPE =>
                  val issueValue = issue.optIssueTypeName.getOrElse("")
                  if (issueValue.trim.isEmpty) changeLog.optNewValue else Some(issueValue)
                case _ => throw new RuntimeException
              }
            case _ => changeLog.optNewValue
          }
        case _ => changeLog.optNewValue
      }
    }

    private[this] def findProperty(comments: Seq[BacklogComment])(field: String): Option[BacklogComment] =
      comments.reverse.find(comment => findProperty(comment)(field))

    private[this] def findProperty(comment: BacklogComment)(field: String): Boolean =
      comment.changeLogs.map(findProperty).exists(_(field))

    private[this] def findProperty(changeLog: BacklogChangeLog)(field: String): Boolean =
      changeLog.field == field
  }

}

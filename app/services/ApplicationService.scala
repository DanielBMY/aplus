package services

import java.util.UUID
import javax.inject.Inject

import anorm.Column.nonNull
import models.{Answer, Application}
import play.api.db.Database
import play.api.libs.json.{Json, JsonConfiguration, JsonNaming}
import anorm._
import anorm.JodaParameterMetaData._
import play.api.libs.json.JodaReads._
import play.api.libs.json.JodaWrites._

@javax.inject.Singleton
class ApplicationService @Inject()(db: Database) {
  import extentions.Anorm._
  import extentions.JsonFormats._

  private implicit val answerReads = Json.reads[Answer]
  private implicit val answerWrite = Json.writes[Answer]

  implicit val answerListParser: anorm.Column[List[Answer]] =
    nonNull { (value, meta) =>
      val MetaDataItem(qualified, nullable, clazz) = meta
      value match {
        case json: org.postgresql.util.PGobject =>
          Right(Json.parse(json.getValue).as[List[Answer]])
        case json: String =>
          Right(Json.parse(json).as[List[Answer]])
        case _ => Left(TypeDoesNotMatch(s"Cannot convert $value: ${className(value)} to List[Answer] for column $qualified"))
      }
    }

  private val simpleApplication: RowParser[Application] = Macro.parser[Application](
    "id",
    "creation_date",
    "creator_user_name",
    "creator_user_id",
    "subject",
    "description",
    "user_infos",
    "invited_users",
    "area",
    "irrelevant",
    "answers",
    "internal_id",
    "closed",
    "seen_by_user_ids"
  )

  def byId(id: UUID, fromUserId: UUID): Option[Application] = db.withConnection { implicit connection =>
    SQL("UPDATE application SET seen_by_user_ids = seen_by_user_ids || {seen_by_user_id}::uuid WHERE id = {id}::uuid RETURNING *")
      .on('id -> id,
          'seen_by_user_id -> fromUserId).as(simpleApplication.singleOpt)
  }

  def allForCreatorUserId(creatorUserId: UUID) = db.withConnection { implicit connection =>
    SQL("SELECT * FROM application WHERE creator_user_id = {creatorUserId}::uuid ORDER BY creation_date DESC")
      .on('creatorUserId -> creatorUserId).as(simpleApplication.*)
  }

  def allForInvitedUserId(invitedUserId: UUID) = db.withConnection { implicit connection =>
    SQL("SELECT * FROM application WHERE invited_users ?? {invitedUserId} ORDER BY creation_date DESC")
      .on('invitedUserId -> invitedUserId).as(simpleApplication.*)
  }

  def allByArea(areaId: UUID) = db.withConnection { implicit connection =>
    SQL("SELECT * FROM application WHERE area = {areaId}::uuid ORDER BY creation_date DESC")
      .on('areaId -> areaId).as(simpleApplication.*)
  }

  def createApplication(newApplication: Application) = db.withConnection { implicit connection =>
    val invitedUserJson = Json.toJson(newApplication.invitedUsers.map {
      case (key, value) =>
         key.toString -> value
    })
    SQL(
      """
          INSERT INTO application VALUES (
            {id}::uuid,
            {creation_date},
            {creator_user_name},
            {creator_user_id}::uuid,
            {subject},
            {description},
            {user_infos},
            {invited_users},
            {area}::uuid
          )
      """).on(
      'id ->   newApplication.id,
      'creation_date -> newApplication.creationDate,
      'creator_user_name -> newApplication.creatorUserName,
      'creator_user_id -> newApplication.creatorUserId,
      'subject -> newApplication.subject,
      'description -> newApplication.description,
      'user_infos -> Json.toJson(newApplication.userInfos),
      'invited_users -> invitedUserJson,
      'area -> newApplication.area
    ).executeUpdate() == 1
  }
  
  def add(applicationId: UUID, answer: Answer) = db.withTransaction { implicit connection =>
    val invitedUserJson = Json.toJson(answer.invitedUsers.map {
      case (key, value) =>
        key.toString -> value
    })
    val irrelevantSQL = if(answer.declareApplicationHasIrrelevant) {
       ", irrelevant = true "
    } else { "" }
    SQL(
      s"""UPDATE application SET answers = answers || {answer}::jsonb,
          invited_users = invited_users || {invited_users}::jsonb $irrelevantSQL
          WHERE id = {id}::uuid
       """
    ).on(
      'id -> applicationId,
      'answer -> Json.toJson(answer),
      'invited_users -> invitedUserJson
    ).executeUpdate()
  }

  def close(applicationId: UUID) = db.withTransaction { implicit connection =>
    SQL(
      """
          UPDATE application SET closed = true
          WHERE id = {id}::uuid
       """
    ).on(
      'id -> applicationId
    ).executeUpdate() == 1
  }
}


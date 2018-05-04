/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.actionml.engines.navhinting

import java.time.OffsetDateTime

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import com.actionml.core.model.GenericEngineParams
import com.actionml.core.store.Store
import com.actionml.core.engine.{Dataset, Event}
import com.actionml.core.validate._
import org.joda.time.DateTime
import org.mongodb.scala.MongoCollection

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.reflectiveCalls

/** Navigation Hinting input data
  * The NavHintingDataset manages User journeys and the hint model. Users are not in-memory. Journeys may be in-memory
  * and persisted after changes accumulate.
  *
  */
class NavHintingDataset(engineId: String, storage: Store)(implicit ec: ExecutionContext) extends Dataset[NHEvent] with JsonParser {

  val activeJourneysDAO = storage.createDao[Journey]("active_journeys")
  val navHintsDAO = storage.createDao[NavHint]("nav_hints")

  private var trailLength: Int = _

  override def init(json: String, deepInit: Boolean = true): Validated[ValidateError, Boolean] = {
    val res = parseAndValidate[GenericEngineParams](json).andThen { p =>
      parseAndValidate[NHAlgoParams](json).andThen { algoParams =>
        trailLength = algoParams.numQueueEvents.getOrElse(50)
        Valid(algoParams)
      }
      Valid(p) // Todo: trailLength may not have been set if algo params is not valid
    }
    if(res.isInvalid) Invalid(ParseError("Error parsing JSON params for numQueueEvents.")) else Valid(true)
  }

  override def destroy() = {
    storage.drop //.dropDatabase(engineId)
  }

  // add one json, possibly an NHEvent, to the beginning of the dataset
  override def input(json: String): Validated[ValidateError, NHEvent] = {
    parseAndValidateInput(json).andThen(persist)
  }


  private def persist(event: NHEvent): Validated[ValidateError, NHEvent] = {
    try {
      event match {
        case event: NHNavEvent => // nav events enqued for each user until conversion
          val conversion = event.properties.flatMap(_.conversion).getOrElse(false)
          val unconvertedJourney = activeJourneysDAO.findOneById(event.entityId)
          if(!conversion) { // store in the user journey queue
            if (unconvertedJourney.nonEmpty) {
              val updatedJourney = enqueueAndUpdate(event, unconvertedJourney)
              if (updatedJourney.nonEmpty) { // existing Journey so updAte in place
                val uj = updatedJourney.get
                activeJourneysDAO.save(uj._id, uj)
                Valid(true)
              } // else the first event for the journey is a conversion so ignore
            } else { // no persisted journey so create it
              activeJourneysDAO.insert(
                Journey(
                  event.entityId,
                  Seq(JourneyStep(event.targetEntityId, OffsetDateTime.parse(event.eventTime)))
                )
              )
              Valid(true)
            }
            Valid(event)
          } else {
            Valid(event)
          }

        case event: HNDeleteEvent => // remove an object, Todo: for a group, will trigger model removal in the Engine
          event.entityType match {
            case "user" =>
              logger.trace(s"Dataset: ${engineId} removing any journey data for user: ${event.entityId}")
              activeJourneysDAO.removeById(event.entityId)
              Valid(event)
            case _ =>
              logger.warn(s"Unrecognized $$delete entityType event: ${event} will be ignored")
              Invalid(ParseError(s"Unrecognized event: ${event} will be ignored"))
          }
        case _ =>
          logger.warn(s"Unrecognized event: ${event} will be ignored")
          Invalid(ParseError(s"Unrecognized event: ${event} will be ignored"))
      }
    } catch {
      case e @ (_ : IllegalArgumentException | _ : ArithmeticException ) =>
        logger.error(s"ISO 8601 Datetime parsing error ignoring input: ${event}", e)
        Invalid(ParseError(s"ISO 8601 Datetime parsing error ignoring input: ${event}"))
      case e: Exception =>
        logger.error(s"Unknown Exception: Beware! trying to recover by ignoring input: ${event}", e)
        Invalid(ParseError(s"Unknown Exception: Beware! trying to recover by ignoring input: ${event}, ${e.getMessage}"))
    }
  }

  override def parseAndValidateInput(json: String): Validated[ValidateError, NHEvent] = {

    parseAndValidate[NHRawEvent](json).andThen { event =>
      event.event match {
        case "$delete" => // remove an object
          event.entityType match {
            case "user"  => // got a user profile update event
              logger.trace(s"Dataset: ${engineId} parsing an $$delete event: ${event.event}")
              parseAndValidate[HNDeleteEvent](json)
          }

        case _ => // default is a self describing usage event, kept as a stream
          logger.trace(s"Dataset: ${engineId} parsing a usage event: ${event.event}")
          parseAndValidate[NHNavEvent](json)
      }
    }
  }

  def enqueueAndUpdate(event: NHNavEvent, maybeJourney: Option[Journey]): Option[Journey] = {
    if (maybeJourney.nonEmpty) {
      val journey = maybeJourney.get
      Some(
        Journey(
          journey._id,
          (journey.trail :+ JourneyStep(event.targetEntityId, OffsetDateTime.parse(event.eventTime))).takeRight(trailLength)
        )
      )
    } else None
  }

}

//case class CBGroupInitProperties( p: Map[String, Seq[String]])

/* NHEvent partially parsed from the Json:
{
  "event" : "$set", //"$unset means to remove some properties (not values) from the object
  "entityType" : "user"
  "entityId" : "amerritt",
  "properties" : {
    "gender": ["male"],
    "country" : ["Canada"],
    "otherContextFeatures": ["A", "B"]
  }
  "eventTime" : "2014-11-02T09:39:45.618-08:00",
  "creationTime" : "2014-11-02T09:39:45.618-08:00", // ignored, only created by PIO
}
 */


//case class UsersDAO(usersColl: MongoCollection)  extends SalatDAO[User, String](usersColl)

/*case class NHUserUpdateEvent(
    entityId: String,
    // Todo:!!! this is the way they should be encoded, fix when we get good JSON
    properties: Option[Map[String, Seq[String]]] = None,
    //properties: Option[Map[String, String]],
    eventTime: String)
  extends NHEvent

case class NHUserUnsetEvent(
    entityId: String,
    // Todo:!!! this is teh way they should be encoded, fix when we get good JSON
    // properties: Option[Map[String, Seq[String]]],
    properties: Option[Map[String, Any]] = None,
    eventTime: String)
  extends NHEvent
*/

/*
Some values are ignored
{
  "event" : "nav-event",
  "entityType" : "user", // value ignored
  "entityId" : "pferrel",
  "targetEntityType" : "???", // value ignored
  "targetEntityId" : "nav-1", // assumed to be a nav-event-id
  "properties" : {
    "conversion" : true | false
  }
  "eventTime" : "2014-11-02T09:39:45.618-08:00",
}
*/
case class NHNavEventProperties(
  conversion: Option[Boolean] = Some(false))

case class NHNavEvent(
    event: String,
    entityId: String,
    targetEntityId: String,
    properties: Option[NHNavEventProperties],
    eventTime: String)
  extends NHEvent {

/*  def toNavEvent: NavEvent = {
    NavEvent(
      _id = this.event,
      userId = this.entityId,
      itemId = this.targetEntityId,
      converted = this.properties.conversion.getOrElse(false),
      eventTime = new DateTime(this.eventTime)
    )
  }
*/
}

case class NavEvent(
  event: String,
  userId: String,
  itemId: String,
  converted: String,
  eventTime: DateTime)

//case class NavEventDAO(eventColl: MongoCollection) extends SalatDAO[NavEvent, ObjectId](eventColl)

case class JourneyStep(
    navId: String,
    timeStamp: OffsetDateTime)

case class Journey(
    _id: String, // User-id we are recording nav events for
    trail: Seq[JourneyStep]) // most recent nav events

case class NavHint(
    _id: String, // nav-id
    weight: Double) // scored nav-ids



/* HNUser Comes in NHEvent partially parsed from the Json:
{
  "event" : "$delete", // removes user: ammerrit
  "entityType" : "user"
  "entityId" : "pferrel",
}
 */
case class HNDeleteEvent(
    entityId: String,
    entityType: String)
  extends NHEvent


// allows us to look at what kind of specialized event to create
case class NHRawEvent (
    //eventId: String, // not used in Harness, but allowed for PIO compatibility
    event: String,
    entityType: String,
    entityId: String,
    targetEntityId: Option[String] = None,
    properties: Map[String, Boolean] = Map.empty,
    eventTime: String) // ISO8601 date
  extends NHEvent

trait NHEvent extends Event
package io.coral.actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import akka.pattern.ask
import io.coral.actors.Messages._
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.immutable.SortedSet
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

class CoralActorSpec(_system: ActorSystem)
  extends TestKit(_system)
  with ImplicitSender
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with ScalaFutures {

  def this() = this(ActorSystem("coral"))

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(100 millis)
  implicit val formats = org.json4s.DefaultFormats

  val root = TestActorRef[CoralActor](Props(new MinimalCoralActor), "coral")

  class MinimalCoralActor extends CoralActor(JObject()) {

  }

  def createCoralActor(props: Props = null, name: String = ""): CoralActor = {
    val _props = if (props != null) props else Props(new MinimalCoralActor)
    val ref =
      if (name == "") TestActorRef[CoralActor](_props)
      else TestActorRef[CoralActor](_props, root, name)
    ref.underlyingActor
  }

  def getJson(actor: CoralActor): JObject = {
    val result = actor.self ? Get()
    Await.result(result.mapTo[JObject], timeout.duration)
  }

  "A CoralActor" should {

    "Have a 'jsonDef' method that returns the json definition" in {
      val testJson = parse( """{ "test": "jsonDef" }""").asInstanceOf[JObject]
      class TestCoralActor extends CoralActor(testJson) {

      }
      val coral = createCoralActor(Props(new TestCoralActor()))
      coral.jsonDef should be(testJson)
    }

    "Have an 'askActor' method to ask another actor by name" in {
      val coral = createCoralActor()
      val probe = TestProbe()
      val result = coral.askActor(probe.ref.path.toString, "ask")
      probe.expectMsg("ask")
      probe.reply("ask:response")
      assert(result.isCompleted && result.value == Some(Success("ask:response")))
    }

    "Have a 'tellActor' method to tell another by name" in {
      val coral = createCoralActor()
      val probe = TestProbe()
      coral.tellActor(probe.ref.path.toString, "tell")
      probe.expectMsg("tell")
    }

    "Have an 'in' method" in {
      val coral = createCoralActor()
      val probe = TestProbe()
      coral.in(10 millis) {
        coral.tellActor(probe.ref.path.toString, "msg2")
      }
      probe.expectMsg(100 millis, "msg2")
    }

    "Provide it's description for a 'Get' message" in {
      val testJson = parse( """{ "test": "get" }""").asInstanceOf[JObject]
      val testState = Map("key" -> JDouble(2.71))
      class TestCoralActor extends CoralActor(testJson) {

        override def state: Map[String, JValue] = testState
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      coral.self ! Get()
      expectMsg(testJson
        merge render("attributes", render("state", render(testState)))
        merge render("attributes", render("input", JObject())))
    }

    "Handle any JSON message" in {
      val testJson: JValue = parse( """{ "test": "emit" }""")
      class TestCoralActor extends MinimalCoralActor {
        override def trigger = json => Future.successful(Some(testJson.merge(json)))
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val probe = TestProbe()
      val json = parse( """{ "something": "else" }""")
      val expected = testJson.merge(json)
      coral.emitTargets += probe.ref
      coral.self ! json
      probe.expectMsg(expected)
    }

    "Ignore an incomplete JSON message (that is, makes trigger returns nothing)" in {
      val testJson: JValue = parse( """{ "test": "incomplete" }""")
      class TestCoralActor extends MinimalCoralActor {
        override def trigger = _ => Future.successful(None)
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val probe = TestProbe()
      coral.emitTargets += probe.ref
      coral.self ! testJson
      probe.expectNoMsg(100 millis)
    }

    "Ignore an JSON message that makes trigger fail" in {
      val testJson: JValue = parse( """{ "test": "fail" }""")
      class TestCoralActor extends MinimalCoralActor {
        override def trigger = _ => Future.failed({
          new Exception
        })
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val probe = TestProbe()
      coral.emitTargets += probe.ref
      coral.self ! testJson
      probe.expectNoMsg(100 millis)
    }

    "Handle a 'Shunt' message" in {
      val testJson: JValue = parse( """{ "test": "emit" }""")
      class TestCoralActor extends MinimalCoralActor {
        override def trigger = json => Future.successful(Some(testJson.merge(json)))
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val json = parse( """{ "something": "else" }""")
      val expected = testJson.merge(json)
      coral.self ! Shunt(json.asInstanceOf[JObject])
      expectMsg(expected)
    }

    "Ignore a 'Shunt' message that triggers none" in {
      val testJson: JValue = parse( """{ "test": "emit" }""")
      class TestCoralActor extends MinimalCoralActor {
        override def trigger = _ => Future.successful(None)
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val json = parse( """{ "something": "else" }""")
      coral.self ! Shunt(json.asInstanceOf[JObject])
      expectNoMsg(100 millis)
    }

    "Have 'noProcess' produce empty future option" in {
      val coral = createCoralActor()
      val result = coral.trigger(parse( """{"test": "whatever"}""").asInstanceOf[JObject])
      whenReady(result) {
        value => value should be(Some(JNothing))
      }
    }
  }

  "A CoralActor trigger" should {

    "Be activated after a 'Trigger' message" in {
      val testJson: JValue = parse( """{ "test": "trigger" }""")
      class TestCoralActor extends MinimalCoralActor {
        var wasExecuted = false

        override def trigger = _ => Future.successful({
          wasExecuted = true; None
        })
      }

      val coral = createCoralActor(Props(new TestCoralActor))

      coral.jsonData(parse("{}").asInstanceOf[JObject])
      expectNoMsg(100 millis)
      coral.asInstanceOf[TestCoralActor].wasExecuted should be(true)
    }

    "Be defined in concrete implementations of 'trigger'" in {
      val testJson: JValue = parse( """{ "test": "trigger2" }""")
      class TestCoralActor extends MinimalCoralActor {
        var wasExecuted = false

        override def trigger = _ => Future.successful {
          wasExecuted = true; None
        }
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val result = coral.trigger(testJson.asInstanceOf[JObject])
      whenReady(result) {
        value => value should be(None)
      }
      coral.asInstanceOf[TestCoralActor].wasExecuted should be(true)
    }

    "Ignore an 'UpdateProperties' message without information" in {
      val coral = createCoralActor()
      coral.self ! UpdateProperties(parse( """{}""").asInstanceOf[JObject])
      expectMsg(true)
      assert((getJson(coral) \ "attributes" \ "input").extract[JObject] == JObject())
    }

    "Handle an 'UpdateProperties' message with trigger connection" in {
      val coral = createCoralActor()
      val other = createCoralActor(name = "test1")
      val inputJson2 = """{"trigger":"test1"}"""
      coral.self ! UpdateProperties(parse( s"""{"attributes": {"input": $inputJson2}}}""").asInstanceOf[JObject])
      other.emitTargets should be(SortedSet(coral.self))
      expectMsg(true)
      assert((getJson(coral) \ "attributes" \ "input").extract[JObject] == parse(inputJson2))
    }

    "Ignore an 'UpdateProperties' message with trigger connection of unknown type" in {
      val coral = createCoralActor()
      coral.self ! UpdateProperties(parse( """{"attributes": {"input": {"trigger":{"in": {"type": "doesnotexist"}}}}}""").asInstanceOf[JObject])
      expectMsg(true)
      assert((getJson(coral) \ "attributes" \ "input").extract[JObject] == JObject())
    }

  }

  "CoralActor trigger with emit" should {

    "Emit to actors registered with a 'RegisterActor' message" in {
      val coral = createCoralActor()
      val probe = TestProbe()
      coral.self ! RegisterActor(probe.ref)
      coral.emitTargets should be(SortedSet(probe.ref))
    }

    "Have a 'emit' method" in {
      val coral = createCoralActor()
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      coral.emitTargets += probe2.ref
      coral.emitTargets += probe1.ref
      val json = parse( """{ "test": "transmit" }""")
      coral.emit(json)
      probe1.expectMsg(json)
      probe2.expectMsg(json)
      coral.emit(JNothing)
      probe1.expectNoMsg(100 millis)
    }

  }

  "A CoralActor state" should {

    "Have a default implementation of no state" in {
      val coral = createCoralActor(Props(new MinimalCoralActor))
      coral.state should be(Map.empty)
    }

    "Be defined in concrete implementations of 'state'" in {
      val testState = Map("key" -> JDouble(1.6))
      class TestCoralActor extends MinimalCoralActor {
        override def state: Map[String, JValue] = testState
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      coral.state should be(testState)
    }

    "Be accessible with a 'GetField' message" in {
      val testValue = JDouble(3.1)
      val testState = Map("key" -> testValue)
      class TestCoralActor extends MinimalCoralActor {
        override def state: Map[String, JValue] = testState
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      coral.self ! GetField("key")
      expectMsg(testValue)
      coral.self ! GetField("non-existing key")
      expectMsg(JNothing)
    }

  }

  "CoralActor collect" should {

    "Obtain state of other actors with 'getCollectInputField'" in {
      val coral = createCoralActor()
      val probe = TestProbe()
      val path = probe.ref.path.toString
      coral.collectSources = Map("test" -> path)
      val result = coral.getCollectInputField[Int]("test", "", "testField")
      probe.expectMsg(GetFieldBy("testField", ""))
      probe.reply(JInt(42))
      whenReady(result) {
        value => value should be(Some(42))
      }
    }

    "Obtain state of other actors with subpath with 'getCollectInputField'" in {
      val coral = createCoralActor()
      val probe = TestProbe()
      val path = probe.ref.path.toString
      coral.collectSources = Map("test" -> path)
      val result = coral.getCollectInputField[Int]("test", "dummypath", "testField")
      probe.expectMsg(GetFieldBy("testField", "dummypath"))
      probe.reply(JInt(48))
      whenReady(result) {
        value => value should be(Some(48))
      }
    }

    "Fail to obtain state of actors not in collectSources with 'getCollectInputField'" in {
      val coral = createCoralActor()
      val thrown = intercept[Exception] {
        val result = coral.getCollectInputField[Int]("test", "", "testField")
      }
      thrown.getMessage should be("Collect actor not defined")
    }

    "Handle an 'UpdateProperties' message with collect connection" in {
      val coral = createCoralActor()
      val jsonDef = """{"collect":{"source": 12}}"""
      coral.self ! UpdateProperties(parse( s"""{"attributes": {"input": $jsonDef}}""").asInstanceOf[JObject])
      coral.collectSources should be(Map("source" -> "/user/coral/12"))
      expectMsg(true)
      assert((getJson(coral) \ "attributes" \ "input").extract[JObject] == parse(jsonDef))
    }

  }

  "A CoralActor timer" should {

    "Be defined in concrete implementations of 'timer'" in {
      val testJson: JValue = parse( """{ "test": "timer" }""")
      class TestCoralActor extends MinimalCoralActor {
        override def timer: TimerType = Future.successful(Some(testJson))
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val result = Await.result(coral.timer, timeout.duration)
      result should be(Some(testJson))
    }

    "Accept a timer parameter" in {
      val createJson = parse( s"""{ "attributes": {"timeout": { "duration": 0.23, "mode": "exit" } } }""").asInstanceOf[JObject]
      val testJson: JValue = parse( """{ "test": "timer2" }""")
      class TestCoralActor extends CoralActor(createJson) {
        override def timer: TimerType = Future.successful(Some(testJson))
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      coral.timerDuration should be(0.23d)
      coral.timerMode should be(TimerExit)
      val probe = TestProbe()
      coral.emitTargets += probe.ref
      probe.expectMsg(testJson)
    }

    "Stop with timer mode 'exit'" in {
      val createJson = parse( s"""{ "attributes": {"timeout": { "duration": 0.53, "mode": "exit" } } }""").asInstanceOf[JObject]
      val testJson = parse( """{ "test": "stopped" }""")
      class TestCoralActor extends CoralActor(createJson) {

        override def postStop(): Unit = emit(testJson)
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val probe = TestProbe()
      coral.emitTargets += probe.ref
      probe.expectMsg(testJson)
    }

    "Emit the timer json when timer mode = 'continue'" in {
      val createJson = parse( s"""{ "attributes": {"timeout": { "duration": 0.5, "mode": "continue" } } }""").asInstanceOf[JObject]
      val testJson = parse( """{ "test": "timer3" }""")
      class TestCoralActor extends CoralActor(createJson) {
        override def timer: TimerType = Future.successful(Some(testJson))
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val probe = TestProbe()
      coral.emitTargets = SortedSet(probe.ref)
      probe.expectMsg(testJson)
    }

    "Ignore an unknown timer mode" in {
      val createJson = parse( s"""{ "attributes": {"timeout": { "duration": 5, "mode": "doesnotexist" } } }""").asInstanceOf[JObject]
      val testJson = parse( """{ "test": "timer4" }""")
      class TestCoralActor extends CoralActor(createJson) {
        override def timer: TimerType = Future.successful(Some(testJson))
      }
      val coral = createCoralActor(Props(new TestCoralActor))
      val probe = TestProbe()
      coral.emitTargets += probe.ref
      coral.timerMode should be(TimerNone)
      coral.self ! TimeoutEvent
      probe.expectMsg(testJson)
      probe.expectNoMsg(100 millis)
    }

  }
}
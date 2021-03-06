package com.softwaremill.sttp.testing

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext.Implicits.global
import com.softwaremill.sttp._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}

class SttpBackendStubTests extends FlatSpec with Matchers with ScalaFutures {
  private val testingStub = SttpBackendStub(HttpURLConnectionBackend())
    .whenRequestMatches(_.uri.path.startsWith(List("a", "b")))
    .thenRespondOk()
    .whenRequestMatches(_.uri.paramsMap.get("p").contains("v"))
    .thenRespond(10)
    .whenRequestMatches(_.method == Method.GET)
    .thenRespondServerError()
    .whenRequestMatchesPartial({
      case r
          if r.method == Method.POST && r.uri.path.endsWith(
            List("partial10")) =>
        Response(Right(10), 200, Nil, Nil)
      case r
          if r.method == Method.POST && r.uri.path.endsWith(
            List("partialAda")) =>
        Response(Right("Ada"), 200, Nil, Nil)
    })

  "backend stub" should "use the first rule if it matches" in {
    implicit val b = testingStub
    val r = sttp.get(uri"http://example.org/a/b/c").send()
    r.is200 should be(true)
    r.body should be('left)
  }

  it should "use subsequent rules if the first doesn't match" in {
    implicit val b = testingStub
    val r = sttp
      .get(uri"http://example.org/d?p=v")
      .response(asString.map(_.toInt))
      .send()
    r.body should be(Right(10))
  }

  it should "use the first specified rule if multiple match" in {
    implicit val b = testingStub
    val r = sttp.get(uri"http://example.org/a/b/c?p=v").send()
    r.is200 should be(true)
    r.body should be('left)
  }

  it should "use the default response if no rule matches" in {
    implicit val b = testingStub
    val r = sttp.put(uri"http://example.org/d").send()
    r.code should be(404)
  }

  it should "wrap responses in the desired monad" in {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val b = SttpBackendStub(new FutureMonad())
    val r = sttp.post(uri"http://example.org").send()
    r.futureValue.code should be(404)
  }

  it should "use rules in partial function" in {
    implicit val s = testingStub
    val r = sttp.post(uri"http://example.org/partial10").send()
    r.is200 should be(true)
    r.body should be(Right(10))

    val ada = sttp.post(uri"http://example.org/partialAda").send()
    ada.is200 should be(true)
    ada.body should be(Right("Ada"))
  }

  it should "handle exceptions thrown instead of a response (synchronous)" in {
    implicit val s = SttpBackendStub(HttpURLConnectionBackend())
      .whenRequestMatches(_ => true)
      .thenRespond(throw new TimeoutException())

    a[TimeoutException] should be thrownBy {
      sttp.get(uri"http://example.org").send()
    }
  }

  it should "handle exceptions thrown instead of a response (asynchronous)" in {
    implicit val s = SttpBackendStub(new FutureMonad())
      .whenRequestMatches(_ => true)
      .thenRespond(throw new TimeoutException())

    val result = sttp.get(uri"http://example.org").send()
    result.failed.futureValue shouldBe a[TimeoutException]
  }

  it should "try to convert a basic response to a mapped one" in {
    implicit val s = SttpBackendStub(HttpURLConnectionBackend())
      .whenRequestMatches(_ => true)
      .thenRespond("10")

    val result = sttp
      .get(uri"http://example.org")
      .mapResponse(_.toInt)
      .mapResponse(_ * 2)
      .send()

    result.body should be(Right(20))
  }

  private val testingStubWithFallback = SttpBackendStub
    .withFallback(testingStub)
    .whenRequestMatches(_.uri.path.startsWith(List("c")))
    .thenRespond("ok")

  "backend stub with fallback" should "use the stub when response for a request is defined" in {
    implicit val b = testingStubWithFallback

    val r = sttp.post(uri"http://example.org/c").send()
    r.body should be(Right("ok"))
  }

  it should "delegate to the fallback for unhandled requests" in {
    implicit val b = testingStubWithFallback

    val r = sttp.post(uri"http://example.org/a/b").send()
    r.is200 should be(true)
  }

  private val s = "Hello, world!"
  private val adjustTestData = List[(Any, ResponseAs[_, _], Any)](
    (s, IgnoreResponse, Some(())),
    (s, ResponseAsString(Utf8), Some(s)),
    (s.getBytes(Utf8), ResponseAsString(Utf8), Some(s)),
    (new ByteArrayInputStream(s.getBytes(Utf8)),
     ResponseAsString(Utf8),
     Some(s)),
    (10, ResponseAsString(Utf8), None),
    ("10",
     MappedResponseAs(ResponseAsString(Utf8), (_: String).toInt),
     Some(10)),
    (10, MappedResponseAs(ResponseAsString(Utf8), (_: String).toInt), None)
  )

  behavior of "tryAdjustResponseBody"

  for {
    (body, responseAs, expectedResult) <- adjustTestData
  } {
    it should s"adjust $body to $expectedResult when specified as $responseAs" in {
      SttpBackendStub.tryAdjustResponseBody(responseAs, body) should be(
        expectedResult)
    }
  }
}

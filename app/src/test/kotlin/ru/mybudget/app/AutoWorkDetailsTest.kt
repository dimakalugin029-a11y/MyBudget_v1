package ru.mybudget.app
import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.auto.AutoWorkDetails
class AutoWorkDetailsTest {
 @Test fun commentsAndAllWorksRemainVisible() {
  val result=AutoWorkDetails.parse("Service note\nOil — 2 250,50 ₽\nFilter — 300 ₽")
  assertEquals("Service note",result.comment)
  assertEquals(listOf("Oil","Filter"),result.items.map { it.first })
  assertEquals(2250.50,result.items.first().second,0.0)
 }
 @Test fun separatorInsideNameUsesLastSeparator() { assertEquals("Repair — wheel",AutoWorkDetails.item("Repair — wheel — 100 ₽")!!.first) }
 @Test fun unrecognizedLegacyLinesAreNotDropped() {
  val raw="Old repair\nWork — price unknown"
  val result=AutoWorkDetails.parse(raw)
  assertEquals(raw,result.comment);assertTrue(result.items.isEmpty())
 }
 @Test fun emptyDescriptionAndZeroPriceHandled() {
  assertTrue(AutoWorkDetails.parse("").items.isEmpty())
  assertEquals(0.0,AutoWorkDetails.item("Warranty — 0 ₽")!!.second,0.0)
 }
}

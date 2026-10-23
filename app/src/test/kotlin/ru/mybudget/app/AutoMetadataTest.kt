package ru.mybudget.app
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.auto.*
import ru.mybudget.app.data.*
class AutoMetadataTest {
 @Test fun oldInsuranceJsonKeepsOsagoAndEmptyOptionalFields() {
  val old=Gson().fromJson("""{"id":1,"vehicleId":1,"startDateEpochDay":1,"endDateEpochDay":366,"amount":100,"categoryId":-1,"paymentMethod":"cash"}""",VehicleInsuranceEntity::class.java)
  val entry=AutoEntry.from(old)
  assertEquals("osago",entry.insuranceType)
  assertEquals("",entry.policyNumber);assertEquals("",entry.insuranceCompany);assertEquals("",entry.insurancePhone)
 }
 @Test fun oldFuelJsonKeepsWorkingWithoutNewFields() {
  val old=Gson().fromJson("""{"vehicleId":1,"dateEpochDay":1,"liters":10,"pricePerLiter":50,"amount":500,"categoryId":-1}""",VehicleFuelLogEntity::class.java)
  assertEquals("",AutoEntry.from(old).fuelType);assertEquals("",AutoEntry.from(old).fuelStation)
 }
 @Test fun policyAndFuelMetadataSurviveDomainMapping() {
  val policy=VehicleInsuranceEntity(vehicleId=1,startDateEpochDay=1,endDateEpochDay=366,amount=100.0,categoryId=-1,insuranceType="casco",insuranceCompany="Company",policyNumber="AA 0001",insurancePhone="+7 123")
  val entry=AutoEntry.from(Gson().fromJson(Gson().toJson(policy),VehicleInsuranceEntity::class.java))
  assertEquals("casco",entry.insuranceType);assertEquals("AA 0001",entry.policyNumber);assertEquals("+7 123",entry.insurancePhone)
  val fuel=VehicleFuelLogEntity(vehicleId=1,dateEpochDay=1,liters=10.0,pricePerLiter=50.0,amount=500.0,categoryId=-1,fuelType="Дизель",fuelStation="Station")
  assertEquals("Дизель",AutoEntry.from(fuel).fuelType);assertEquals("Station",AutoEntry.from(fuel).fuelStation)
 }
}

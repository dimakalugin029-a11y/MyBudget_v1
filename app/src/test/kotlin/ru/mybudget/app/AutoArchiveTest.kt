package ru.mybudget.app

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.auto.*
import ru.mybudget.app.data.*

class AutoArchiveTest {
    private val today=20000L
    private val car=VehicleEntity(id=1,name="Car")
    private val insurance=VehicleInsuranceEntity(vehicleId=1,startDateEpochDay=today-300,
        endDateEpochDay=today+7,amount=100.0,categoryId=2)
    @Test fun archivedCarDoesNotGenerateReminder() {
        assertTrue(InsuranceReminderNotifier.filterDue(listOf(insurance),listOf(car.copy(isArchived=true)),today).isEmpty())
    }
    @Test fun archivedInsuranceDoesNotGenerateReminder() {
        assertTrue(InsuranceReminderNotifier.filterDue(listOf(insurance.copy(isArchived=true)),listOf(car),today).isEmpty())
        assertEquals(1,InsuranceReminderNotifier.filterDue(listOf(insurance),listOf(car),today).size)
    }
    @Test fun missingCarDoesNotGenerateUnnamedReminder() {
        assertTrue(InsuranceReminderNotifier.filterDue(listOf(insurance),emptyList(),today).isEmpty())
    }
    @Test fun archiveDoesNotChangeStatisticalContribution() {
        val entries=AutoEntryType.entries.map { AutoEntry(it,vehicleId=1,dateEpochDay=today,
            amount=100.0,paymentMethod=AutoPaymentMethod.CASH) }
        val before=AutoStatisticsHelper.summarize(entries,1,null,null)
        val after=AutoStatisticsHelper.summarize(entries.map { it.copy(isArchived=true) },1,null,null)
        assertEquals(before.totalPaid,after.totalPaid,0.0)
        assertEquals(before.cash,after.cash,0.0)
        assertEquals(400.0,after.totalPaid,0.0)
    }
    @Test fun allEntityMappingsPreserveArchiveState() {
        assertTrue(AutoEntry.from(insurance.copy(isArchived=true)).isArchived)
        assertTrue(AutoEntry.from(VehicleServiceLogEntity(vehicleId=1,dateEpochDay=today,amount=10.0,categoryId=2,isArchived=true)).isArchived)
        assertTrue(AutoEntry.from(VehicleFuelLogEntity(vehicleId=1,dateEpochDay=today,amount=10.0,categoryId=2,liters=1.0,pricePerLiter=10.0,isArchived=true)).isArchived)
        assertTrue(AutoEntry.from(VehicleRepairEntity(vehicleId=1,dateEpochDay=today,amount=10.0,categoryId=2,isArchived=true)).isArchived)
    }
    @Test fun oldJsonDefaultsToActiveAndNewJsonKeepsArchive() {
        val gson=Gson()
        assertFalse(gson.fromJson("""{"id":1,"name":"Old car","sortOrder":0}""",VehicleEntity::class.java).isArchived)
        assertTrue(gson.fromJson(gson.toJson(car.copy(isArchived=true)),VehicleEntity::class.java).isArchived)
        val old=gson.toJsonTree(insurance.copy(isArchived=true)).asJsonObject.apply { remove("isArchived") }
        assertFalse(gson.fromJson(old,VehicleInsuranceEntity::class.java).isArchived)
    }
}

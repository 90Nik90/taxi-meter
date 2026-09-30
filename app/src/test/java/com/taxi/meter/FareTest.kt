package com.taxi.meter

import com.taxi.meter.data.CalcInput
import com.taxi.meter.data.ExtraService
import com.taxi.meter.data.PaymentMethod
import com.taxi.meter.data.Profile
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.data.calculateFare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FareTest {

    private val profile = Profile(
        name = "Тест",
        pricePerKm = 15.0,
        pricePerIdleMinute = 2.0,
        minPrice = 60.0,
        minDistanceKm = 2.0,
    )

    @Test
    fun `короткая поездка тарифицируется по минимальной цене`() {
        val fare = profile.calculateFare(distanceKm = 1.2, idleSeconds = 0)
        assertEquals(60.0, fare.total, 0.001)
        assertTrue(fare.minPriceApplied)
    }

    @Test
    fun `ровно минимальное расстояние это ещё минимальная цена`() {
        val fare = profile.calculateFare(distanceKm = 2.0, idleSeconds = 0)
        assertEquals(60.0, fare.total, 0.001)
        assertTrue(fare.minPriceApplied)
    }

    @Test
    fun `сверх минимального расстояния считается по цене за км`() {
        // 60 + (10 - 2) * 15 = 180
        val fare = profile.calculateFare(distanceKm = 10.0, idleSeconds = 0)
        assertEquals(8.0, fare.billedKm, 0.001)
        assertEquals(180.0, fare.total, 0.001)
        assertTrue(!fare.minPriceApplied)
    }

    @Test
    fun `расстояние оплачивается по факту, до метра`() {
        // Ровно на границе минимального расстояния платных километров ещё нет
        assertEquals(0.0, profile.calculateFare(2.0, 0).billedKm, 0.001)
        assertEquals(60.0, profile.calculateFare(2.0, 0).total, 0.001)
        // Десять метров сверх — десять метров и оплачены, а не целый километр
        assertEquals(0.01, profile.calculateFare(2.01, 0).billedKm, 0.0001)
        assertEquals(60.15, profile.calculateFare(2.01, 0).total, 0.001)
        // Половина километра — половина цены
        assertEquals(67.5, profile.calculateFare(2.5, 0).total, 0.001)
        assertEquals(75.0, profile.calculateFare(3.0, 0).total, 0.001)
        assertEquals(75.15, profile.calculateFare(3.01, 0).total, 0.001)
    }

    @Test
    fun `начатая минута простоя оплачивается целиком`() {
        assertEquals(0, profile.calculateFare(0.0, 0).billedIdleMinutes)
        assertEquals(1, profile.calculateFare(0.0, 1).billedIdleMinutes)
        assertEquals(1, profile.calculateFare(0.0, 60).billedIdleMinutes)
        assertEquals(2, profile.calculateFare(0.0, 61).billedIdleMinutes)
        // 61 секунда = 2 минуты * 2 грн, плюс минимальная цена 60
        assertEquals(64.0, profile.calculateFare(0.0, 61).total, 0.001)
    }

    @Test
    fun `простой добавляется поверх стоимости расстояния`() {
        // 180 + 5 мин * 2 = 190
        val fare = profile.calculateFare(distanceKm = 10.0, idleSeconds = 300)
        assertEquals(180.0, fare.distancePart, 0.001)
        assertEquals(10.0, fare.idlePart, 0.001)
        assertEquals(190.0, fare.total, 0.001)
    }

    @Test
    fun `при нулевом минимальном расстоянии минимальная цена работает как порог`() {
        val p = profile.copy(minDistanceKm = 0.0)
        // 1 км * 15 = 15, что ниже порога 60
        assertEquals(60.0, p.calculateFare(1.0, 0).total, 0.001)
        // 10 км * 15 = 150, порог не применяется
        assertEquals(150.0, p.calculateFare(10.0, 0).total, 0.001)
        // 9.5 км — это ровно 9.5 км, а не десятый начатый километр
        assertEquals(9.5, p.calculateFare(9.5, 0).billedKm, 0.001)
        assertEquals(142.5, p.calculateFare(9.5, 0).total, 0.001)
    }

    @Test
    fun `услуги прибавляются к минимальной цене поездки`() {
        // Доплаты по умолчанию: дети 20, животные 30, багаж 20
        val children = profile.calculateFare(1.0, 0, setOf(ExtraService.CHILDREN))
        assertEquals(20.0, children.servicesPart, 0.001)
        assertEquals(80.0, children.total, 0.001)

        // Несколько услуг складываются
        val all = profile.calculateFare(
            1.0, 0,
            setOf(ExtraService.CHILDREN, ExtraService.PETS, ExtraService.LUGGAGE),
        )
        assertEquals(70.0, all.servicesPart, 0.001)
        assertEquals(130.0, all.total, 0.001)
    }

    @Test
    fun `услуги не отменяют плату за километры и простой`() {
        // 60 + 30 (животные) + 8 км * 15 + 5 мин * 2 = 220
        val fare = profile.calculateFare(10.0, 300, setOf(ExtraService.PETS))
        assertEquals(30.0, fare.servicesPart, 0.001)
        assertEquals(210.0, fare.distancePart, 0.001)
        assertEquals(10.0, fare.idlePart, 0.001)
        assertEquals(220.0, fare.total, 0.001)
    }

    @Test
    fun `цены услуг берутся из общих настроек, а не из тарифа`() {
        val prices = ServicePrices(children = 5.0, pets = 7.0, luggage = 9.0)
        val fare = profile.calculateFare(
            1.0, 0,
            setOf(ExtraService.PETS, ExtraService.LUGGAGE),
            prices,
        )
        assertEquals(16.0, fare.servicesPart, 0.001)
        assertEquals(76.0, fare.total, 0.001)
    }

    @Test
    fun `без выбранных услуг доплат нет`() {
        assertEquals(0.0, profile.calculateFare(10.0, 0).servicesPart, 0.001)
        assertEquals(180.0, profile.calculateFare(10.0, 0).total, 0.001)
    }

    @Test
    fun `итог округляется до копеек`() {
        // Метры дают дробные копейки; водителю называть их нечем,
        // поэтому в сумме остаются две цифры после запятой
        // 60 + 0.1234 км * 15 = 61.851
        val fare = profile.calculateFare(2.1234, 0)
        assertEquals(61.85, fare.distancePart, 0.0001)
        assertEquals(61.85, fare.total, 0.0001)
    }
}

class CalcInputTest {

    private val profile = Profile(
        name = "Тест",
        pricePerKm = 15.0,
        pricePerIdleMinute = 2.0,
        minPrice = 60.0,
        minDistanceKm = 2.0,
    )

    @Test
    fun `запятая с клавиатуры читается как точка`() {
        assertEquals(12.4, CalcInput(distanceText = "12,4").distanceKm, 0.0001)
        assertEquals(12.4, CalcInput(distanceText = "12.4").distanceKm, 0.0001)
    }

    @Test
    fun `пустые и мусорные поля дают нули`() {
        assertEquals(0.0, CalcInput().distanceKm, 0.0001)
        assertEquals(0, CalcInput().idleMinutes)
        assertEquals(0.0, CalcInput(distanceText = ".").distanceKm, 0.0001)
        assertEquals(0, CalcInput(idleText = "-3").idleMinutes)
    }

    @Test
    fun `введённые минуты ожидания считаются целиком`() {
        val input = CalcInput(distanceText = "10", idleText = "5")
        assertEquals(300L, input.idleSeconds)
        // 60 + 8 км * 15 + 5 мин * 2 = 190
        assertEquals(190.0, input.fare(profile, ServicePrices())!!.total, 0.001)
    }

    @Test
    fun `без тарифа расчёта нет`() {
        assertNull(CalcInput(distanceText = "10").fare(null, ServicePrices()))
    }

    @Test
    fun `пустой ввод нечего скидывать`() {
        assertTrue(CalcInput().isEmpty)
        assertTrue(!CalcInput(distanceText = "1").isEmpty)
        assertTrue(!CalcInput(payment = PaymentMethod.CASH).isEmpty)
    }
}


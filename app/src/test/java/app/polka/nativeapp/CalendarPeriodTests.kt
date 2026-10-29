package app.polka.nativeapp
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class CalendarPeriodTests {
 @Test fun rangeIsInclusiveAndPagesAreBounded(){val p=CalendarPeriod(LocalDate.of(2023,3,12),LocalDate.of(2025,2,9));assertEquals(2,p.pages().size);assertTrue(p.pages().all{it.size<=12});assertEquals(YearMonth.of(2023,3),p.pages().first().first());assertEquals(YearMonth.of(2025,2),p.pages().last().last());assertTrue(p.contains(LocalDate.of(2023,3,12)));assertFalse(p.contains(LocalDate.of(2023,3,11)))}
 @Test(expected=IllegalArgumentException::class) fun reversedRangeRejected(){CalendarPeriod(LocalDate.of(2025,3,1),LocalDate.of(2025,2,1))}
 @Test(expected=IllegalArgumentException::class) fun futureDateRejected(){CalendarPeriod(LocalDate.now(),LocalDate.now().plusDays(1))}
}

package com.gfs.iyango.data.repository

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.data.db.IYangoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RoomTimetableRepository] 行为验证（P2-13，Robolectric）：
 * - setCurrent 事务后全表仅一行 isCurrent；
 * - 新建首条课表自动置当前；
 * - 删除当前课后 observeCurrent 发射 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimetableRepositoryTest {

    private lateinit var db: IYangoDatabase
    private lateinit var repository: RoomTimetableRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTimetableRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `新建首条课表自动置为当前`() = runTest {
        val id = repository.upsert(Timetable(name = "我的课表", source = "LOCAL"))
        val current = repository.observeCurrent().first()
        assertEquals(id, current?.id)
        assertTrue(current!!.isCurrent)
    }

    @Test
    fun `setCurrent 事务后全表仅一行 isCurrent`() = runTest {
        val first = repository.upsert(Timetable(name = "A", source = "LOCAL", isCurrent = true))
        val second = repository.upsert(Timetable(name = "B", source = "LOCAL"))
        repository.setCurrent(second)

        val all = repository.observeAll().first()
        assertEquals(listOf(first, second), all.map { it.id })
        // 精确一行 isCurrent，且为目标行。
        assertEquals(listOf(second), all.filter { it.isCurrent }.map { it.id })
        assertEquals(second, repository.observeCurrent().first()?.id)
    }

    @Test
    fun `删除当前课后 observeCurrent 发射 null`() = runTest {
        val first = repository.upsert(Timetable(name = "A", source = "LOCAL"))
        repository.upsert(Timetable(name = "B", source = "LOCAL", isCurrent = true))
        repository.delete(repository.observeCurrent().first()!!.id)

        assertNull(repository.observeCurrent().first())
        // 另一课表仍在，但"当前"由用户显式切换，不自动顶替。
        assertEquals(listOf(first), repository.observeAll().first().map { it.id })
    }
}

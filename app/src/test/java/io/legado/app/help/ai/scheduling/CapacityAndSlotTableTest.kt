package io.legado.app.help.ai.scheduling

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CapacityTable] / [SlotTable] / [PoolFullCalculator] 单测。
 *
 * 纯内存数据结构，无需 Robolectric。
 */
class CapacityAndSlotTableTest {

    private lateinit var capacity: CapacityTable
    private lateinit var slots: SlotTable

    @Before
    fun setUp() {
        capacity = CapacityTable()
        slots = SlotTable()
    }

    // ============================================================
    // CapacityTable —— 三态语义
    // ============================================================

    @Test
    fun capacityUnknownProviderReturnsDefault() {
        assertEquals(1, capacity.get("unknown", CapacityTable.MEDIA_VIDEO))
        assertEquals(2, capacity.get("unknown", CapacityTable.MEDIA_IMAGE))
    }

    @Test
    fun capacityKnownProviderKnownMediaReturnsRegistered() {
        capacity.set("agnes", CapacityTable.MEDIA_VIDEO, 3)
        assertEquals(3, capacity.get("agnes", CapacityTable.MEDIA_VIDEO))
    }

    @Test
    fun capacityKnownProviderUnknownMediaReturnsZero() {
        capacity.set("agnes", CapacityTable.MEDIA_VIDEO, 3)
        // agnes 已登记 video，但未登记 audio → 0（不支持）
        assertEquals(0, capacity.get("agnes", CapacityTable.MEDIA_AUDIO))
    }

    @Test
    fun capacityZeroMeansUnsupported() {
        capacity.set("grok", CapacityTable.MEDIA_IMAGE, 0)
        assertEquals(0, capacity.get("grok", CapacityTable.MEDIA_IMAGE))
    }

    @Test
    fun capacityReplaceOverwritesAll() {
        capacity.set("agnes", CapacityTable.MEDIA_VIDEO, 3)
        capacity.replace(mapOf(
            "ark" to CapacityTable.MEDIA_VIDEO to 2,
            "agnes" to CapacityTable.MEDIA_IMAGE to 1
        ))
        assertEquals("旧配置应被清空", 2, capacity.get("ark", CapacityTable.MEDIA_VIDEO))
        assertEquals("新配置生效", 1, capacity.get("agnes", CapacityTable.MEDIA_IMAGE))
        assertEquals("agnes video 被清空后变 0", 0, capacity.get("agnes", CapacityTable.MEDIA_VIDEO))
    }

    @Test(expected = IllegalArgumentException::class)
    fun capacitySetNegativeThrows() {
        capacity.set("x", CapacityTable.MEDIA_VIDEO, -1)
    }

    // ============================================================
    // SlotTable —— register/release/hasRoom
    // ============================================================

    @Test
    fun slotRegisterAndHasRoom() {
        val job = SupervisorJob()
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", job)

        assertTrue("有 1 个 inflight，capacity=2 时应有空位", slots.hasRoom("agnes", CapacityTable.MEDIA_VIDEO, 2))
        assertFalse("capacity=1 时已满", slots.hasRoom("agnes", CapacityTable.MEDIA_VIDEO, 1))
    }

    @Test
    fun slotReleaseEmptyBucketNotResidual() {
        val job = SupervisorJob()
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", job)
        slots.release("agnes", CapacityTable.MEDIA_VIDEO, "job_1")

        // bucket 应被整体删除，occupiedProviders 不应返回空 provider
        assertTrue(slots.occupiedProviders(CapacityTable.MEDIA_VIDEO).isEmpty())
        assertTrue(slots.hasRoom("agnes", CapacityTable.MEDIA_VIDEO, 1))
    }

    @Test
    fun slotReleaseNonExistentIsNoop() {
        slots.release("nonexistent", CapacityTable.MEDIA_VIDEO, "job_x")
        // 不抛异常即可
    }

    @Test
    fun slotOccupiedProvidersOnlyReturnsProvidersWithInflight() {
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", SupervisorJob())
        slots.register("ark", CapacityTable.MEDIA_VIDEO, "job_2", SupervisorJob())
        slots.register("agnes", CapacityTable.MEDIA_IMAGE, "job_3", SupervisorJob())

        val videoProviders = slots.occupiedProviders(CapacityTable.MEDIA_VIDEO)
        assertEquals(setOf("agnes", "ark"), videoProviders)

        val imageProviders = slots.occupiedProviders(CapacityTable.MEDIA_IMAGE)
        assertEquals(setOf("agnes"), imageProviders)
    }

    @Test
    fun slotFindByJobId() {
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", SupervisorJob())
        slots.register("ark", CapacityTable.MEDIA_IMAGE, "job_2", SupervisorJob())

        assertEquals("agnes" to CapacityTable.MEDIA_VIDEO, slots.findByJobId("job_1"))
        assertEquals("ark" to CapacityTable.MEDIA_IMAGE, slots.findByJobId("job_2"))
        assertEquals(null, slots.findByJobId("nonexistent"))
    }

    @Test
    fun slotActiveJobIds() {
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", SupervisorJob())
        slots.register("ark", CapacityTable.MEDIA_IMAGE, "job_2", SupervisorJob())

        assertEquals(setOf("job_1", "job_2"), slots.activeJobIds())
    }

    @Test
    fun slotTotalInflight() {
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", SupervisorJob())
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_2", SupervisorJob())
        slots.register("ark", CapacityTable.MEDIA_IMAGE, "job_3", SupervisorJob())

        assertEquals(3, slots.totalInflight())

        slots.release("agnes", CapacityTable.MEDIA_VIDEO, "job_1")
        assertEquals(2, slots.totalInflight())
    }

    // ============================================================
    // PoolFullCalculator
    // ============================================================

    @Test
    fun poolFullCalculatorReturnsEmptyWhenNoOccupied() {
        val full = PoolFullCalculator.calculate(CapacityTable.MEDIA_VIDEO, slots, capacity)
        assertTrue("无 inflight 时无池满", full.isEmpty())
    }

    @Test
    fun poolFullCalculatorReturnsProviderWhenAtCapacity() {
        capacity.set("agnes", CapacityTable.MEDIA_VIDEO, 1)
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", SupervisorJob())

        val full = PoolFullCalculator.calculate(CapacityTable.MEDIA_VIDEO, slots, capacity)
        assertEquals("capacity=1 + 1 inflight 应池满", setOf("agnes"), full)
    }

    @Test
    fun poolFullCalculatorExcludesProviderWithRoom() {
        capacity.set("agnes", CapacityTable.MEDIA_VIDEO, 2)
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", SupervisorJob())

        val full = PoolFullCalculator.calculate(CapacityTable.MEDIA_VIDEO, slots, capacity)
        assertTrue("capacity=2 + 1 inflight 不应池满", full.isEmpty())
    }

    @Test
    fun poolFullCalculatorExcludesUnsupportedProvider() {
        // capacity=0 的 provider 不算池满（是 unsupported，由 worker 标 failed）
        capacity.set("grok", CapacityTable.MEDIA_IMAGE, 0)
        slots.register("grok", CapacityTable.MEDIA_IMAGE, "job_1", SupervisorJob())

        val full = PoolFullCalculator.calculate(CapacityTable.MEDIA_IMAGE, slots, capacity)
        assertTrue("capacity=0 不算池满", full.isEmpty())
    }

    @Test
    fun poolFullCalculatorOnlyChecksSpecifiedMediaType() {
        capacity.set("agnes", CapacityTable.MEDIA_VIDEO, 1)
        capacity.set("agnes", CapacityTable.MEDIA_IMAGE, 1)
        // video 池满，image 无 inflight
        slots.register("agnes", CapacityTable.MEDIA_VIDEO, "job_1", SupervisorJob())

        val videoFull = PoolFullCalculator.calculate(CapacityTable.MEDIA_VIDEO, slots, capacity)
        assertEquals(setOf("agnes"), videoFull)

        val imageFull = PoolFullCalculator.calculate(CapacityTable.MEDIA_IMAGE, slots, capacity)
        assertTrue("image lane 不受 video lane 影响", imageFull.isEmpty())
    }
}

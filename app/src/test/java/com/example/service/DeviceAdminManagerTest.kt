package com.example.service

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.service.dpc.DeviceAdminManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceAdminManagerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun testDeviceAdminComponentIsCorrect() {
        val admin = DeviceAdminManager.getAdminComponent(context)
        assertEquals("com.example.service.dpc.FocusDeviceAdminReceiver", admin.className)
        assertEquals(context.packageName, admin.packageName)
    }

    @Test
    fun testInactiveAdminStateReportsCorrectly() {
        val isActive = DeviceAdminManager.isAdminActive(context)
        assertFalse(isActive)
    }

    @Test
    fun testCreateAddAdminIntent() {
        val explanation = "Focus Shield requires Device Admin permission."
        val intent = DeviceAdminManager.createAddAdminIntent(context, explanation)
        assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, intent.action)
        val component = intent.getParcelableExtra<android.content.ComponentName>(DevicePolicyManager.EXTRA_DEVICE_ADMIN)
        assertNotNull(component)
        assertEquals("com.example.service.dpc.FocusDeviceAdminReceiver", component?.className)
        assertEquals(explanation, intent.getStringExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION))
    }

    @Test
    fun testActiveAdminStateAndDeactivation() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val shadowDpm = Shadows.shadowOf(dpm)
        val admin = DeviceAdminManager.getAdminComponent(context)

        shadowDpm.setActiveAdmin(admin)
        assertTrue(DeviceAdminManager.isAdminActive(context))

        DeviceAdminManager.deactivateAdmin(context)
        assertFalse(DeviceAdminManager.isAdminActive(context))
    }

    @Test
    fun testSyncAndVerifyPolicyState() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = AppRepository(database.appDao(), context)
        repository.initializeDefaultsIfEmpty()

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val shadowDpm = Shadows.shadowOf(dpm)
        val admin = DeviceAdminManager.getAdminComponent(context)

        // When setting is true and admin is active -> stays true
        shadowDpm.setActiveAdmin(admin)
        repository.setSetting(AppRepository.KEY_UNINSTALL_PROTECTION, "true")
        DeviceAdminManager.syncAndVerifyPolicyState(context, repository)
        assertEquals("true", repository.getSetting(AppRepository.KEY_UNINSTALL_PROTECTION))

        // When admin becomes inactive -> sync auto sets setting to false
        DeviceAdminManager.deactivateAdmin(context)
        DeviceAdminManager.syncAndVerifyPolicyState(context, repository)
        assertEquals("false", repository.getSetting(AppRepository.KEY_UNINSTALL_PROTECTION))

        database.close()
    }
}
